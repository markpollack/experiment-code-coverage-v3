"""Read-only acceptance of the first Codex policy; fixture proof is never native qualification."""
import argparse
import base64
import json
from pathlib import Path
from accept_native_coverage import Evidence, jury_verdict, require
from codex_candidate_surface import inventory, validate

def evaluate(root, manifest_path):
    root=Path(root);app=root/'application';e=Evidence();m=e.json(manifest_path)
    reservation=e.json(root/'candidate-reservation.json')
    require(reservation['manifestSha256']==__import__('hashlib').sha256(e.read(manifest_path)).hexdigest(),'Wrong manifest reservation')
    require(reservation['runId']==m['runId'] and m['outputDirectory']==str(root),'Wrong run/output identity')
    e.references(m['files']);launch=e.json(app/'launch.json')
    require(launch['sourceCommit']==m['sourceCommit'],'Source commit differs')
    require(launch['pinned']['beforeTree']=='6d9d5311952f7a022e5d30af8693d11d932186f9','Wrong pinned subject')
    require(e.json(app/'judges/judging-mode.json')['judgeInferenceCalls']==0,'Judge inference is not allowed')
    name=base64.urlsafe_b64encode(m['runId'].encode()).decode().rstrip('=')
    result_path=app/'results'/name/'run.json';result=e.json(result_path);e.references(result)
    require(len(result['items'])==1,'Exactly one task required');row=result['items'][0]
    require(row['workflowState']=='SUCCEEDED' and row['workspaceState']=='RETAINED','Workflow not accepted')
    require(len(row['invocations'])==1,'Exactly one original invocation required');inv=row['invocations'][0]
    require(inv['responseState']=='SUCCEEDED' and inv['captureState']=='COMPLETE','Original result/capture not accepted')
    original=e.json(app/'native-attempt.json');response=e.json(app/'observed-client-response.json')
    require(original['termination']['cleanupConfirmed'] is True,'Client cleanup not confirmed')
    require(original['decision']['outcomeType']=='Success' and response['successful'] is True,'Original outcome not successful')
    reader=e.json(root/'independent-reader/reader-receipt.json');e.references(reader['verified_files'])
    require(reader['verifier_exit']==0,'Independent complete archive refused')
    require(reader['invocation_id']==inv['attempt']['invocationId'] and reader['journal_run_uid']==original['executionId']
            and reader['decision_id']==original['decision']['decisionId']==response['decisionId'],'Foreign archive/decision identity')
    require(reader['export_sha256']==__import__('hashlib').sha256(e.read(root/'independent-reader/canonical.jsonl')).hexdigest(),'Changed canonical export')
    before=e.json(app/'candidate-before.json');patch=e.json(app/'candidate-patch.json');actual=validate(before,inventory(app/'workspace'))
    require(patch['changes']==actual['changes'],'Final subject patch changed')
    verified=e.json(app/'verification-after-build.json');require(verified['changes']==[],'Submitted test inputs changed during independent builds')
    require(verified['afterInventory']==inventory(app/'verification'),'Verification tree changed after final inventory')
    jury=jury_verdict(row,result_path,app,e)
    command=e.json(app/'judges/independent-build-command.json');require(command['argv']==['./mvnw','clean','test','jacoco:report'],'Wrong independent foreground command')
    sandbox=e.json(app/'judges/independent-build-sandbox.json');require(sandbox['network']=='UNSHARED' and sandbox['pidNamespace']=='UNSHARED','Restricted build missing')
    act=e.json(root/'act/manifest.json');require(set(act['tables'])=={'tool_uses','item_results','runs','judge_details','cores','events','observations','edges','state_sequence'},'Incomplete ACT tables')
    require(act['inputs_sha256']==__import__('hashlib').sha256(e.read(root/'act-inputs.json')).hexdigest(),'ACT input association differs')
    # ACT's retained file roster is relative to its own output directory.
    for name,detail in act['files'].items():
        require(isinstance(detail,str) and len(detail)==64,'Invalid ACT file hash');e.read(root/'act'/name,detail)
    return {'kind':'v3-codex-first-policy-acceptance/1','status':'ACCEPTED','mode':m['mode'],
            'nativeQualification':m['mode']=='NATIVE','semanticQuality':'NOT_ASSESSED','judgeInferenceCalls':0,
            'runId':m['runId'],'executionId':original['executionId'],'decisionId':reader['decision_id'],
            'jury':jury,'testPatchChanges':len(patch['changes']),'archive':'INDEPENDENTLY_ACCEPTED',
            'cost':'UNKNOWN','outerCleanup':'REQUIRES_FINAL_GUARD_RECEIPT','files':list(e.files.values())}

def main():
    p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--manifest',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    try:result=evaluate(a.root,a.manifest)
    except Exception as error:result={'kind':'v3-codex-first-policy-acceptance/1','status':'REJECTED','reason':str(error)}
    with a.output.open('x') as out:json.dump(result,out,indent=2)
    if result['status']!='ACCEPTED':raise SystemExit(1)
if __name__=='__main__':main()
