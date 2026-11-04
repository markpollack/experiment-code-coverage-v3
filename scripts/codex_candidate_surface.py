"""Final-inventory acceptance for the separately approved first Codex policy."""
import argparse
import json
import re
import stat
from pathlib import Path
import importlib.util
_spec=importlib.util.spec_from_file_location("candidate_core",Path(__file__).with_name("verify_codex_candidate.py"))
_core=importlib.util.module_from_spec(_spec);_spec.loader.exec_module(_core)
confined,is_test=_core.confined,_core.is_test

def inventory(root):
    root=Path(root)
    if root.is_symlink():raise ValueError('Symbolic inventory root')
    if not root.is_dir():raise ValueError('Ordinary directory inventory root required')
    result=_core.inventory(root)
    result['rootMode']=stat.S_IMODE(root.stat().st_mode)
    return result

def apply_patch(subject,verification,before,after,patch):
    if inventory(subject)!=after:raise ValueError('Subject changed after verification')
    if inventory(verification)['rootMode']!=before['rootMode']:
        raise ValueError('Fresh verification root mode differs')
    clean=lambda value:{k:v for k,v in value.items() if k!='rootMode'}
    _core.apply_patch(subject,verification,clean(before),clean(after),patch)
    return inventory(verification)

TARGET_FILES = {'target/checkstyle-cachefile','target/checkstyle-checker.xml','target/checkstyle-header.txt','target/checkstyle-result.xml','target/test-ids'}
TARGET_ROOTS = {'classes', 'test-classes', 'generated-sources', 'generated-test-sources',
                'maven-status', 'surefire', 'surefire-reports'}

def generated(name):
    parts = name.split('/')
    return (bool(re.fullmatch(r'target/test-ids/junit-platform-unique-ids-[0-9]+\.txt',name)) or name in TARGET_FILES or name == 'target' or name == 'target/jacoco.exec' or name == 'target/site' or name == 'target/site/jacoco' or name.startswith('target/site/jacoco/') or
            len(parts) > 1 and parts[0] == 'target' and parts[1] in TARGET_ROOTS)


def validate(before, after):
    if before.get('rootMode') is None or before['rootMode']!=after.get('rootMode'):
        raise ValueError('Inventory root mode missing or changed')
    if before['root'] != after['root']:
        raise ValueError('Different subject identity')
    changes, outputs = [], []
    for name in sorted(before['entries'].keys() | after['entries'].keys()):
        old, new = before['entries'].get(name), after['entries'].get(name)
        if not confined(name):
            raise ValueError('Unconfined inventory entry')
        if any(item and item['type'] in ('symlink', 'special') for item in (old, new)):
            raise ValueError('Symlink or special entry: ' + name)
        if old == new:
            continue
        if old and new and (old['type'] != new['type'] or old['mode'] != new['mode']):
            raise ValueError('Type or mode changed: ' + name)
        if new and new['mode'] not in ((0o755, 0o775) if new['type'] == 'directory' else (0o644, 0o664)):
            raise ValueError('Unexpected new entry mode: ' + name)
        if generated(name):
            outputs.append(name)
        elif not is_test(name):
            raise ValueError('Protected authored path changed: ' + name)
        else:
            changes.append({'path': name, 'before': old, 'after': new})
    return {'kind': 'v3-first-codex-patch/1', 'changes': changes, 'generatedExcluded': outputs,
            'policy': 'FINAL_INVENTORY_TEST_PATCH', 'commandInterpretation': 'NOT_A_GATE',
            'limits': 'Surviving effects only; transient or escaped effects are not excluded'}


def main():
    p=argparse.ArgumentParser();p.add_argument('--subject',type=Path,required=True)
    p.add_argument('--before',type=Path,required=True);p.add_argument('--output',type=Path)
    p.add_argument('--verification',type=Path);a=p.parse_args()
    if a.output is None:
        with a.before.open('x') as out:json.dump(inventory(a.subject),out,indent=2)
        return
    before=json.loads(a.before.read_text());after=inventory(a.subject);patch=validate(before,after)
    if a.verification:patch['verificationInventory']=apply_patch(a.subject,a.verification,before,after,patch)
    patch['afterInventory']=after
    with a.output.open('x') as out:json.dump(patch,out,indent=2)

if __name__ == '__main__':main()
