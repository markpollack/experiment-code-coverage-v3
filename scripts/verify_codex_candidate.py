"""Deterministic candidate edit/command checks; never launches a provider or grants native authority."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shlex
import shutil
import importlib.util
import stat

BUILD = './mvnw clean test jacoco:report'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def inventory(root):
    root = Path(root).resolve(strict=True)
    entries = {}
    for directory, dirs, files in os.walk(root, followlinks=False):
        for name in sorted(dirs + files):
            path = Path(directory) / name
            relative = path.relative_to(root).as_posix()
            info = path.lstat()
            item = {'mode': stat.S_IMODE(info.st_mode)}
            if path.is_symlink():
                item.update(type='symlink', target=os.readlink(path))
            elif path.is_dir():
                item.update(type='directory')
            elif path.is_file():
                item.update(type='file', sha256=digest(path.read_bytes()), bytes=info.st_size)
            else:
                item.update(type='special')
            entries[relative] = item
    return {'kind': 'v3-path-inventory/1', 'root': str(root), 'entries': entries}


def is_test(path):
    return path == 'src/test' or path.startswith('src/test/')


def generated(path):
    return path == 'target' or path.startswith('target/')


def confined(path):
    value = PurePosixPath(path)
    return not value.is_absolute() and '..' not in value.parts and path not in ('', '.')


def commands(evidence, workspace, edited):
    if (not isinstance(evidence, dict) or evidence.get('kind') != 'v3-command-observations/1'
            or evidence.get('complete') is not True or not isinstance(evidence.get('sourceSha256'), str)
            or len(evidence['sourceSha256']) != 64 or any(c not in '0123456789abcdef' for c in evidence['sourceSha256'])
            or not isinstance(evidence.get('operations'), list)):
        raise ValueError('Complete declared command evidence and SHA256 digest are required')
    if not workspace or evidence.get('workspace') != workspace:
        raise ValueError('Command workspace differs from inventoried subject')
    if edited and not evidence['operations']:
        raise ValueError('Edited result requires operation evidence')
    if evidence.get('sourceManifest'):
        spec = importlib.util.spec_from_file_location('codex_projection', Path(__file__).with_name('project_codex_commands.py'))
        projector = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(projector)
        if evidence != projector.project(evidence['sourceManifest'], workspace):
            raise ValueError('Command projection differs from original source population')
    # Source/assembly qualification and completeness of this population are separate admission gates.
    for operation in evidence['operations']:
        if operation.get('cwd') != workspace:
            raise ValueError('Operation working directory differs from subject')
        if not operation.get('sourceId') or operation.get('complete') is not True:
            raise ValueError('Missing source occurrence or completion evidence')
        if operation.get('tool') == 'apply_patch':
            patch = operation.get('patch', '')
            paths = [line.split(': ', 1)[1] for line in patch.splitlines()
                     if line.startswith(('*** Add File: ', '*** Update File: ', '*** Delete File: ', '*** Move to: '))]
            if not paths or any(not confined(path) or not path.startswith('src/test/') for path in paths):
                raise ValueError('Disallowed or missing patch path evidence')
            continue
        if operation.get('tool') != 'exec_command' or operation.get('background') is not False:
            raise ValueError('Disallowed or background operation')
        command = operation.get('command')
        if not isinstance(command, str) or not isinstance(operation.get('exitCode'), int):
            raise ValueError('Missing command or exit evidence')
        if command == BUILD:
            continue
        # A deliberately small candidate read vocabulary. This observes, never prevents execution.
        if any(token in command for token in (';', '|', '&', '>', '<', '$', '`', '\n', '\r', '*', '?')):
            raise ValueError('Compound, expanded or redirected command is disallowed')
        argv = shlex.split(command)
        allowed = argv == ['pwd'] or argv == ['rg', '--files']
        read_paths = argv[2:] if argv[:2] == ['cat', '--'] else argv[1:]
        allowed |= len(argv) >= 2 and argv[0] == 'cat' and bool(read_paths) and all(confined(p) and not p.startswith('-') for p in read_paths)
        allowed |= len(argv) == 3 and argv[:2] == ['rg', '--files'] and confined(argv[2])
        if not allowed:
            raise ValueError('Command is outside the candidate read/build vocabulary')
    return len(evidence['operations'])


def validate(before, after, command_evidence):
    old, new = before['entries'], after['entries']
    edited = any(not generated(name) and old.get(name) != new.get(name) for name in old.keys() | new.keys())
    if before.get('root') != after.get('root'):
        raise ValueError('Before and after subject identity differs')
    operation_count = commands(command_evidence, after.get('root'), edited)
    patch, generated_paths = [], []
    for name in sorted(old.keys() | new.keys()):
        previous, current = old.get(name), new.get(name)
        if not confined(name):
            raise ValueError('Unconfined inventory path')
        if (previous or {}).get('type') in ('symlink', 'special') or (current or {}).get('type') in ('symlink', 'special'):
            raise ValueError('Symlink or special path is not acceptable: ' + name)
        if generated(name):
            if previous != current:
                generated_paths.append(name)
            continue
        if previous == current:
            continue
        if not is_test(name):
            raise ValueError('Protected authored path changed: ' + name)
        if previous and current and (previous['type'] != current['type'] or previous['mode'] != current['mode']):
            raise ValueError('File type or mode changed: ' + name)
        if current and current['type'] == 'file' and current['mode'] not in (0o644, 0o664):
            raise ValueError('New test file has disallowed mode: ' + name)
        patch.append({'path': name, 'before': previous, 'after': current})
    edit_paths = {line.split(': ', 1)[1] for operation in command_evidence['operations']
                  if operation.get('tool') == 'apply_patch' for line in operation.get('patch', '').splitlines()
                  if line.startswith(('*** Add File: ', '*** Update File: ', '*** Delete File: ', '*** Move to: '))}
    changed_files = {item['path'] for item in patch if (item['after'] or item['before'])['type'] == 'file'}
    if not changed_files <= edit_paths:
        raise ValueError('Changed test files lack observed edit operations')
    return {'kind': 'v3-candidate-patch/1', 'changes': patch, 'generatedExcluded': generated_paths,
            'observedOperations': operation_count, 'nativeAcceptance': False,
            'commandEvidenceState': 'SOURCE_BOUND' if command_evidence.get('sourceManifest') else 'STRUCTURAL_ONLY',
            'sourceBindingVerified': bool(command_evidence.get('sourceManifest')),
            'limits': 'After-state and observed operations only; no proof of absent transient writes or escaped effects'}


def apply_patch(subject, verification, before, after, patch):
    """Transfer only validated test changes to a fresh pre-populated pinned verification tree."""
    subject, verification = Path(subject).resolve(strict=True), Path(verification).resolve(strict=True)
    if subject == verification or subject in verification.parents or verification in subject.parents:
        raise ValueError('Distinct isolated subject and verification trees required')
    if inventory(subject) != after:
        raise ValueError('Subject changed after verification')
    baseline = inventory(verification)
    for name, record in before['entries'].items():
        if not generated(name) and baseline['entries'].get(name) != record:
            raise ValueError('Fresh verification copy differs from original: ' + name)
    if any(not generated(name) and name not in before['entries'] for name in baseline['entries']):
        raise ValueError('Unexpected path in fresh verification copy')
    # Validate the supplied delta again; callers cannot smuggle a protected destination.
    actual = [{'path': name, 'before': before['entries'].get(name), 'after': after['entries'].get(name)}
              for name in sorted(before['entries'].keys() | after['entries'].keys())
              if not generated(name) and before['entries'].get(name) != after['entries'].get(name)]
    if patch['changes'] != actual or any(not is_test(item['path']) for item in actual):
        raise ValueError('Patch differs from validated test changes')
    for item in sorted(actual, key=lambda value: value['path'].count('/'), reverse=True):
        if item['after'] is None:
            target = verification / item['path']
            target.rmdir() if target.is_dir() else target.unlink()
    for item in actual:
        target, record = verification / item['path'], item['after']
        if record is None:
            continue
        if record['type'] == 'directory':
            target.mkdir(parents=True, exist_ok=True)
        elif record['type'] == 'file':
            data = (subject / item['path']).read_bytes()
            if digest(data) != record['sha256']:
                raise ValueError('Test bytes changed during transfer')
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        else:
            raise ValueError('Unsupported patch file type')
        target.chmod(record['mode'])
    result = inventory(verification)
    for name, record in after['entries'].items():
        if not generated(name) and result['entries'].get(name) != record:
            raise ValueError('Transferred patch readback differs')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--subject', required=True, type=Path)
    parser.add_argument('--before', required=True, type=Path)
    parser.add_argument('--commands', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--verification', type=Path)
    args = parser.parse_args()
    before = json.loads(args.before.read_text())
    after = inventory(args.subject)
    observations = json.loads(args.commands.read_text())
    patch = validate(before, after, observations)
    if args.verification:
        patch['verificationInventory'] = apply_patch(args.subject, args.verification, before, after, patch)
    patch['beforeSha256'] = digest(args.before.read_bytes())
    patch['commandsSha256'] = digest(args.commands.read_bytes())
    patch['afterInventory'] = after
    with args.output.open('x') as stream:
        json.dump(patch, stream, indent=2)


if __name__ == '__main__':
    main()
