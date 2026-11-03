"""Exact first Codex launch guard. Preparation never supplies Owner activation."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time

SAFE_ENV = {'HOME','CODEX_HOME','PATH','LANG','LC_ALL','TZ','TMPDIR','MAVEN_ARGS','MAVEN_OPTS','JAVA_HOME','MAVEN_USER_HOME','PYTHONDONTWRITEBYTECODE'}

def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def write(path,value):
    with Path(path).open('x') as out:json.dump(value,out,indent=2)
def require(value,message):
    if not value:raise ValueError(message)

def validate(data):
    require(data.get('kind')=='v3-codex-first-launch/1','Wrong candidate manifest')
    require(data.get('nativeAuthorization') is False and data.get('approvedSubmissions')==0,'Prepared manifest must remain inert')
    require(data.get('proposedSubmissions')==1 and data.get('generations')==1 and data.get('retries')==0,'One fresh generation without retry required')
    require(data.get('policy')=='FINAL_INVENTORY_TEST_PATCH','Wrong first-run policy')
    require(data.get('mode') in ('NATIVE','FIXTURE'),'Explicit candidate mode required')
    require(data.get('model')=='gpt-6-luna' and data.get('effort')=='high','Pinned model/effort required')
    require(data.get('bounds')=={'subjectSeconds':900,'cleanupSeconds':15,'pipelineSeconds':1800,'buildSeconds':600,'buildGraceSeconds':15},'Exact supported bounds required')
    env=data['environment'];require(set(env)<=SAFE_ENV and {'HOME','CODEX_HOME','PATH','MAVEN_ARGS'}<=set(env),'Environment allowlist required')
    require(all(isinstance(x,str) and '\0' not in x for x in env.values()),'Invalid environment')
    selected={}
    for ref in data['files']:
        path=Path(ref['path']);require(path.is_absolute() and path.resolve(strict=True)==path and path.is_file(),'Ordinary exact artifact required')
        require(sha(path)==ref['sha256'],'Changed artifact: '+str(path));selected[str(path)]=ref['sha256']
    for key in ('javaExecutable','launcherPython','actPython','timeoutExecutable','codexExecutable','readerSource','mappingScript','candidateSurface','launcher','pipelineLauncher'):
        require(data[key] in selected,'Unhashed launch component: '+key)
    require(data['launcher']==str(Path(__file__).resolve()),'Wrong installed guard')
    for key in ('runtimeClasspath','readerClasspath'):
        require(data[key] and set(data[key])<=selected.keys(),'Unhashed classpath')
    require(data['policyDelta'] in selected,'Unhashed policy delta')
    require(data['environment']['MAVEN_ARGS']=='-o -Dmaven.repo.local='+data['subjectMavenRepository']+' -Dspring-javaformat.skip=true','Exact subject cache required')
    for key in ('subjectMavenRepository','mavenRepository'):
        path=Path(data[key]);require(path.resolve(strict=True)==path and path.is_dir() and os.access(path,os.W_OK),'Writable ordinary prepared cache required')
    require(env.get('MAVEN_USER_HOME')==data['subjectMavenRepository']+'/maven-user-home','Exact prepared wrapper cache required')
    require(data['subjectMavenRepository']!=data['mavenRepository'],'Separate subject and verification caches required')
    root=Path(data['outputDirectory']);require(root.is_absolute() and root.parent.resolve(strict=True)==root.parent,'Exact output parent required')
    for key in ('subjectMavenRepository','mavenRepository'):
        require(not Path(data[key]).is_relative_to(root),'Prepared caches must survive clean outside output')
    require(data['runId'] and data['projectRoot'],'Pinned run/project required')
    return env

def authorize(data,manifest_hash,activation):
    require(activation is not None,'Separate Owner activation required')
    require(activation.get('nativeAuthorization') is True and activation.get('approvedSubmissions')==1,'Exactly one Owner-approved submission required')
    require(activation.get('manifestSha256')==manifest_hash,'Approval manifest hash mismatch')
    require(activation.get('outputDirectory')==data['outputDirectory'] and activation.get('runId')==data['runId'],'Approval output/run mismatch')
    require(bool(activation.get('ownerReference')),'Owner reference required')
    require(activation.get('expiresEpochSeconds',0)>time.time(),'Owner activation expired')
    require(activation.get('acceptedPolicy')==data['policy'] and activation.get('acceptsUncontainedBuild') is True,'Exact policy/build limitation approval required')

def reserve(data,manifest_hash):
    ledger=Path(data['attemptLedger']);require(not ledger.is_symlink(),'Symbolic ledger refused')
    write(ledger,{'kind':'v3-codex-candidate-reserved/1','manifestSha256':manifest_hash,'runId':data['runId'],
                  'reservedAt':time.time(),'submissions':1,'limit':1,'retries':0,'state':'SUBMISSION_RESERVED'})

def preflight(data,env):
    observations=[]
    for args in (['--version'],['login','status']):
        p=subprocess.run([data['codexExecutable'],*args],env=env,capture_output=True,timeout=15)
        text=p.stdout+p.stderr
        valid=p.returncode==0 and (text.strip()==b'codex-cli 0.166.0' if args==['--version'] else b'Logged in using ChatGPT' in text)
        require(valid,'Selected executable version/subscription login preflight failed')
        observations.append({'check':args,'accepted':True,'rawAuthOutputRetained':False})
    return observations

def main():
    p=argparse.ArgumentParser();p.add_argument('--manifest',type=Path,required=True);p.add_argument('--activation',type=Path)
    p.add_argument('--check-only',action='store_true');p.add_argument('--claim',action='store_true');p.add_argument('--application');p.add_argument('--executable');p.add_argument('--candidate-mode');a=p.parse_args()
    data=json.loads(a.manifest.read_text());env=validate(data);digest=sha(a.manifest)
    if a.check_only:print(json.dumps({'status':'VALID_INERT_PACKET','manifestSha256':digest}));return
    if a.claim:
        root=Path(data['outputDirectory']);reservation=json.loads((root/'candidate-reservation.json').read_text())
        require(reservation==json.loads(Path(data['attemptLedger']).read_text()),'Ledger/reservation identity mismatch')
        require(reservation.get('manifestSha256')==digest and reservation.get('runId')==data['runId'],'Foreign reservation')
        require(a.application==str(root/'application') and a.executable==data['codexExecutable'],'Foreign application/executable')
        require(a.candidate_mode==('native' if data['mode']=='NATIVE' else 'fixture'),'Candidate mode mismatch')
        if data['mode']=='NATIVE':
            approval=json.loads((root/'owner-activation.json').read_text());authorize(data,digest,approval)
            require(sha(root/'owner-activation.json')==reservation.get('activationSha256'),'Changed activation')
        write(Path(data['attemptLedger']+'.claimed'),{'manifestSha256':digest,'runId':data['runId'],'claimedAt':time.time()})
        return
    activation=json.loads(a.activation.read_text()) if a.activation else None
    if data['mode']=='NATIVE':authorize(data,digest,activation)
    else:require(activation is None,'Synthetic route must not consume native approval')
    root=Path(data['outputDirectory']);require(not root.exists() and not root.is_symlink(),'Existing output refuses replay')
    checks=preflight(data,env) if data['mode']=='NATIVE' else []
    reserve(data,digest);root.mkdir()
    if activation is not None:
        write(root/'owner-activation.json',activation)
        ledger=json.loads(Path(data['attemptLedger']).read_text());ledger['activationSha256']=sha(root/'owner-activation.json')
        # This is the sole newly created reservation; all child admission remains closed until this flush.
        with Path(data['attemptLedger']).open('w') as out:json.dump(ledger,out,indent=2)
    write(root/'candidate-reservation.json',json.loads(Path(data['attemptLedger']).read_text()))
    write(root/'preflight.json',checks)
    # Wrapper caches are separate: the provider can write only the selected subject cache;
    # verification uses its own cache and an environment without subscription credentials.
    started=time.monotonic();env=dict(env);env['V3_SOFT_DEADLINE_EPOCH_MS']=str(int((time.time()+1785)*1000))
    argv=[data['timeoutExecutable'],'--signal=TERM','--kill-after=15s','1785s',data['launcherPython'],data['pipelineLauncher'],
          '--inside','--manifest',str(a.manifest.resolve()),'--output-dir',str(root),'--run-id',data['runId']]
    with (root/'pipeline.log').open('x') as log:
        child=subprocess.Popen(argv,env=env,start_new_session=True,stdout=log,stderr=subprocess.STDOUT);code=child.wait()
    remaining=False
    try:os.killpg(child.pid,0);remaining=True;os.killpg(child.pid,signal.SIGKILL)
    except ProcessLookupError:pass
    write(root/'outer-receipt.json',{'exit':code,'elapsedSeconds':time.monotonic()-started,'ownedProcessGroup':child.pid,
          'remainingOwnedGroupObserved':remaining,'scope':'Owned group only; escaped processes unqualified'})
    if code or remaining:raise SystemExit(code or 1)

if __name__=='__main__':main()
