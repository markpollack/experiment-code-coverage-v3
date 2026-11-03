"""Final-inventory acceptance for the separately approved first Codex policy."""
import argparse
import json
from pathlib import Path
import importlib.util
_spec=importlib.util.spec_from_file_location("candidate_core",Path(__file__).with_name("verify_codex_candidate.py"))
_core=importlib.util.module_from_spec(_spec);_spec.loader.exec_module(_core)
inventory,apply_patch,confined,is_test=_core.inventory,_core.apply_patch,_core.confined,_core.is_test

TARGET_ROOTS = {'classes', 'test-classes', 'generated-sources', 'generated-test-sources',
                'maven-status', 'surefire', 'surefire-reports'}

def generated(name):
    parts = name.split('/')
    return (name == 'target' or name == 'target/jacoco.exec' or name == 'target/site' or name == 'target/site/jacoco' or name.startswith('target/site/jacoco/') or
            len(parts) > 1 and parts[0] == 'target' and parts[1] in TARGET_ROOTS)


def validate(before, after):
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
