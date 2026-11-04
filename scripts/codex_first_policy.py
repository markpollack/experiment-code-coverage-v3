"""Exact named filesystem policy shared by model-free probes and the native wrapper."""
import json
from pathlib import Path

def profile(workspace, cache):
    rules={'/':'read', str(Path(workspace).resolve()):'write', str(Path(cache).resolve()):'write'}
    encoded='{'+','.join(json.dumps(k)+'='+json.dumps(v) for k,v in rules.items())+'}'
    return ['-c','permissions.v3_first.filesystem='+encoded,'-c','permissions.v3_first.network.enabled=false']

def native_arguments(arguments,workspace,cache):
    args=list(arguments)
    if 'exec' not in args:return args
    if 'resume' in args or any(x.startswith('--dangerously-') or x=='--add-dir' for x in args):
        raise ValueError('Resume or extra authority is outside the one-generation policy')
    for flag,value in (('--ask-for-approval','never'),('-C',str(Path(workspace).resolve())),('--model','gpt-6-luna')):
        if args.count(flag)!=1 or args[args.index(flag)+1]!=value:raise ValueError('Changed exact SDK option: '+flag)
    if '--no-daemon' not in args or 'model_reasoning_effort="high"' not in args:
        raise ValueError('Pinned non-daemon/high-effort route required')
    found=[]
    for option in ('--sandbox','-s'):
        while option in args:
            at=args.index(option)
            if at+1==len(args) or args[at+1]!='workspace-write':raise ValueError('Workspace-write request required')
            found.append(args[at+1]);del args[at:at+2]
    if len(found)!=1:raise ValueError('Exactly one SDK workspace-write selection required')
    at=args.index('exec')+1
    args[at:at]=['--ignore-user-config','--ignore-rules','-c','forced_login_method="chatgpt"',
                '-c','default_permissions="v3_first"','-c','shell_environment_policy.inherit="all"',*profile(workspace,cache)]
    return args
