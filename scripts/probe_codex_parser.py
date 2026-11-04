"""Validate captured exec argv without a prompt, credentials, network or host writes.

This is an explicit offline check, never part of native task activation. The selected
Codex exits on empty forced stdin before starting its in-process client/thread/turn.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def probe(capture, output):
    binary = capture['binary']
    argv = capture['argv']
    if argv[0] != binary or argv[-2:] != ['--', '-'] or argv.count('exec') != 1:
        raise ValueError('Only the captured fresh forced-stdin exec route is supported')
    if any(x in argv for x in ('resume', 'fork', 'review', '--help', '-h', '--version', '-V')):
        raise ValueError('No alternate route or parser-bypass argument permitted')
    if argv.count('--ignore-user-config') != 1:
        raise ValueError('Corrected capture must contain exactly one config-isolation flag')
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False)
    prefix = ['/usr/bin/bwrap', '--unshare-net', '--die-with-parent', '--ro-bind', '/', '/',
              '--tmpfs', '/tmp', '--dir', '/tmp/empty-codex-home',
              '--setenv', 'HOME', '/tmp', '--setenv', 'CODEX_HOME', '/tmp/empty-codex-home', '--']
    virtual_root = capture.get('namespaceRoot')
    if virtual_root is not None:
        root = Path(virtual_root)
        directories = capture.get('virtualDirectories', [])
        if (not root.is_absolute() or Path(binary).is_relative_to(root)
                or not directories or any(not Path(p).is_relative_to(root) for p in directories)):
            raise ValueError('Invalid private parser namespace')
        # Mount over the private preparation root, creating only namespace-local roots.
        prefix[-1:-1] = ['--tmpfs', str(root)]
        for directory in directories:
            prefix[-1:-1] = ['--dir', directory]
    # The stdin pipe carries zero bytes. These are exact capture argv, with controls
    # inserting one flag only. HOME/config overrides are probe isolation, not live policy.
    duplicate = list(argv)
    duplicate.insert(duplicate.index('exec') + 1, '--ignore-user-config')
    invalid = list(argv)
    invalid.insert(invalid.index('exec') + 1, '--v3-parser-must-reject')
    rows = []
    for name, args, code, marker in (
        ('corrected', argv, 1, 'No prompt provided via stdin.'),
        ('original-duplicate', duplicate, 2, "the argument '--ignore-user-config' cannot be used multiple times"),
        ('unknown-option', invalid, 2, "unexpected argument '--v3-parser-must-reject'"),
    ):
        command = prefix + args
        result = subprocess.run(command, input=b'', capture_output=True, timeout=15,
                                env={'PATH': '/usr/bin:/bin', 'LANG': 'C.UTF-8'})
        (output / (name + '.stdout')).write_bytes(result.stdout)
        (output / (name + '.stderr')).write_bytes(result.stderr)
        row = {'name': name, 'command': command, 'stdinBytes': 0, 'exit': result.returncode,
               'stdoutBytes': len(result.stdout), 'expectedMarker': marker,
               'passed': result.returncode == code and not result.stdout and marker.encode() in result.stderr}
        rows.append(row)
        if not row['passed']:
            raise RuntimeError('Unexpected parser result: ' + json.dumps(row))
    proof = {'kind': 'v3-composed-parser-proof/1', 'status': 'PASS',
             'binary': {'path': binary, 'sha256': hashlib.sha256(Path(binary).read_bytes()).hexdigest()},
             'argv': argv, 'controls': rows, 'network': 'UNSHARED',
             'credentialEnvironment': 'EMPTY_HOME_AND_MINIMAL_ENV', 'hostFilesystem': 'READ_ONLY',
             'generationStop': 'empty forced stdin exits before client/thread/turn creation',
             'modelRequests': 0, 'qualificationLimit': 'Parser/config startup admission only; not native task qualification'}
    (output / 'proof.json').write_text(json.dumps(proof, indent=2) + '\n')
    return proof


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--capture', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = probe(json.loads(args.capture.read_text()), args.output)
    print(json.dumps({'status': result['status'], 'modelRequests': result['modelRequests']}))
