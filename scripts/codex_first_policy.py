"""Exact named filesystem policy shared by model-free probes and the native wrapper."""
import json
from pathlib import Path

def profile(workspace, cache):
    rules={'/':'read', str(Path(workspace).resolve()):'write', str(Path(cache).resolve()):'write'}
    encoded='{'+','.join(json.dumps(k)+'='+json.dumps(v) for k,v in rules.items())+'}'
    return ['-c','permissions.v3_first.filesystem='+encoded,'-c','permissions.v3_first.network.enabled=false']

def native_arguments(arguments,workspace,cache):
    args=list(arguments)
    if args in (['--version'],['login','status']):return args
    # This wrapper is qualified for the selected installed SDK's exact stdin route.
    # Fail closed on duplicate/default overrides, aliases, extra authority or transport drift.
    expected=['--no-daemon','--ask-for-approval','never','--sandbox','workspace-write',
              '-C',str(Path(workspace).resolve()),'exec','--ignore-user-config',
              '--model','gpt-6-luna','-c','model_reasoning_effort="high"',
              '--skip-git-repo-check','--json','--','-']
    if args!=expected:
        raise ValueError('Changed exact SDK invocation; one config-isolation flag and stdin transport required')
    at=args.index('--sandbox');del args[at:at+2]
    at=args.index('exec')+1
    args[at:at]=['--ignore-rules','-c','forced_login_method="chatgpt"',
                '-c','default_permissions="v3_first"','-c','shell_environment_policy.inherit="all"',*profile(workspace,cache)]
    return args
