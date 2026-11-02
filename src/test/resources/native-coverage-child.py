#!/usr/bin/env python3
"""Deterministic native-wire stand-in. It never invokes an LLM or real Claude."""
import json, os, sys, time
from pathlib import Path
root = Path.cwd()
(root / 'fixture-pid').write_text(str(os.getpid()))
(root / 'fixture-argv.json').write_text(json.dumps(sys.argv[1:]))
(root / 'fixture-maven-env.json').write_text(json.dumps(os.environ.get('MAVEN_ARGS')))
callback = None
mode = None
next_hook = 0
cases = [('Write', {'file_path': 'src/test/java/Allowed.java'}, 'allow'),
         ('Write', {'file_path': 'pom.xml'}, 'deny'),
         ('Glob', {'pattern': '/outside/*'}, 'deny')]

def send(value):
    print(json.dumps(value), flush=True)

def hook(index):
    tool, args, expected = cases[index]
    send({'type':'control_request','request_id':f'policy-{index}',
          'request':{'subtype':'hook_callback','callback_id':callback,'tool_use_id':f'tool-{index}',
                     'input':{'hook_event_name':'PreToolUse','session_id':'fixture-native',
                              'transcript_path':str(root/'fixture-native.jsonl'),'cwd':str(root),
                              'tool_name':tool,'tool_use_id':f'tool-{index}','tool_input':args}}})

def finish():
    send({'type':'result','subtype':'success','is_error':False,'duration_ms':1,'duration_api_ms':1,
          'num_turns':1,'result':'fixture-complete','session_id':'fixture-native',
          'usage':{'input_tokens':1,'output_tokens':1}})
    sys.exit(0)

for line in sys.stdin:
    request = json.loads(line)
    if request.get('type') == 'control_request' and request['request']['subtype'] == 'initialize':
        callback = request['request']['hooks']['PreToolUse'][0]['hookCallbackIds'][0]
        send({'type':'control_response','response':{'subtype':'success','request_id':request['request_id'],'response':{}}})
    elif request.get('type') == 'user':
        mode = request['message']['content']
        (root/'fixture-prompt.txt').write_text(mode)
        send({'type':'system','subtype':'init','session_id':'fixture-native','cwd':str(root),'tools':[]})
        if mode == 'wait':
            while True: time.sleep(.05)
        if mode == 'overflow':
            sys.stderr.write('x' * (4*1024*1024 + 65536));sys.stderr.flush();finish()
        if mode == 'budget-exhausted':
            send({'type':'result','subtype':'error_max_budget_usd','is_error':True,'duration_ms':1,
                  'duration_api_ms':1,'num_turns':1,'result':'cap reached','session_id':'fixture-native'})
            sys.exit(0)
        if not callback: raise RuntimeError('required hook was not installed')
        hook(0)
    elif request.get('type') == 'control_response' and mode == 'hooks':
        response = request['response']
        expected = cases[next_hook][2]
        payload = response['response']
        if 'continue' in payload and not isinstance(payload['continue'], bool):
            raise RuntimeError('native hook schema: continue must be boolean when present')
        if 'suppressOutput' in payload and not isinstance(payload['suppressOutput'], bool):
            raise RuntimeError('native hook schema: suppressOutput must be boolean when present')
        if any(key in payload for key in ('permission_decision', 'permission_decision_reason', 'updated_input')):
            raise RuntimeError('native hook schema: permission output must stay nested and camelCase')
        if set(payload) != {'hookSpecificOutput'}:
            raise RuntimeError('V3 hook output must omit all absent optional controls')
        specific = payload['hookSpecificOutput']
        if specific.get('hookEventName') != 'PreToolUse':
            raise RuntimeError('native hook schema: required PreToolUse event is missing')
        actual = specific['permissionDecision']
        with (root/'fixture-policy.jsonl').open('a') as log:
            log.write(json.dumps({'requestId':response['request_id'],'actual':actual,'expected':expected,'wire':payload})+'\n')
        if actual != expected: raise RuntimeError(f'policy mismatch {actual} != {expected}')
        next_hook += 1
        if next_hook == len(cases): finish()
        hook(next_hook)
