#!/usr/bin/env python3
"""Explicit synthetic SDK/native-file fixture. Never invokes Claude or another model."""
import json
import os
from pathlib import Path
import sys
import uuid

if sys.argv[1:] == ['--version']:
    print('2.1.316 (synthetic deterministic fixture)')
    raise SystemExit(0)
root = Path(os.environ['V3_FIXTURE_ROOT']).resolve()
session = 'fixture-' + uuid.uuid4().hex
transcripts = root / 'home/.claude/projects/fixture'
transcripts.mkdir(parents=True, exist_ok=True)
(root / 'provider-launches.jsonl').open('a').write(json.dumps({'pid': os.getpid(), 'argv': sys.argv,
    'cwd': str(Path.cwd()), 'maven_args': os.environ.get('MAVEN_ARGS'), 'session': session}) + '\n')
for line in sys.stdin:
    value = json.loads(line)
    if value.get('type') == 'control_request':
        assert value['request']['subtype'] == 'initialize'
        assert value['request']['hooks']['PreToolUse'][0]['hookCallbackIds']
        print(json.dumps({'type': 'control_response', 'response': {'subtype': 'success',
              'request_id': value['request_id'], 'response': {}}}), flush=True)
    elif value.get('type') == 'user':
        (transcripts / (session + '.jsonl')).write_text(json.dumps({'type': 'user', 'uuid': 'fixture-user',
            'sessionId': session, 'version': '2.1.316', 'message': value['message']}) + '\n')
        print(json.dumps({'type': 'system', 'subtype': 'init', 'session_id': session,
                          'claude_code_version': '2.1.316'}), flush=True)
        print(json.dumps({'type': 'result', 'subtype': 'success', 'session_id': session,
            'result': 'Deterministic fixture makes no source edits.', 'is_error': False,
            'usage': {'input_tokens': 1, 'output_tokens': 1}, 'total_cost_usd': 0.01}), flush=True)
        raise SystemExit(0)
