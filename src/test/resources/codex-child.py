#!/usr/bin/python3
# Synthetic Codex exec protocol fixture. It never calls a model.
import json, os, pathlib, sys, time, uuid
if sys.argv[1:] == ['--version']:
    print('codex-cli 0.166.0 (synthetic fixture)')
    raise SystemExit(0)
cwd = pathlib.Path.cwd()
(pathlib.Path(os.environ['V3_CODEX_EVIDENCE'])/'pid').write_text(str(os.getpid()))
with (pathlib.Path(os.environ['V3_CODEX_EVIDENCE'])/'launches.jsonl').open('a') as f: f.write(json.dumps(sys.argv)+'\n')
prompt = sys.stdin.buffer.read()
(pathlib.Path(os.environ['V3_CODEX_EVIDENCE'])/'prompt.bin').write_bytes(prompt)
mode = os.environ.get('V3_CODEX_FIXTURE_MODE','success')
sys.stderr.buffer.write(b'original-stderr-\xff-secret=fixture-token\n')
sys.stderr.buffer.flush()
if mode == 'wait':
    (cwd/'waiting').touch()
    time.sleep(60)
    sys.exit(0)
if os.environ.get('V3_CODEX_TASK_EDITS') == 'true' and mode == 'success':
    corpus = pathlib.Path(__file__).with_name('codex-petclinic-tests')
    manifest = json.loads(pathlib.Path(__file__).with_name('codex-petclinic-tests.json').read_text())
    for entry in manifest['files']:
        destination = cwd/'src/test'/entry['path']
        destination.parent.mkdir(parents=True, exist_ok=True)
        with destination.open('xb') as target:
            target.write((corpus/entry['path']).read_bytes())
n = str(uuid.uuid4())
def emit(obj): print(json.dumps(obj), flush=True)
emit({'type':'thread.started','thread_id':n})
if mode == 'duplicate': emit({'type':'thread.started','thread_id':n})
emit({'type':'turn.started'})
emit({'type':'item.completed','item':{'id':'item_0','type':'agent_message','text': 'answer:'+prompt.decode('utf-8')}})
root = pathlib.Path(os.environ['FIXTURE_SESSIONS']); root.mkdir(parents=True,exist_ok=True)
source = root/('rollout-fixture-'+n+'.jsonl')
template = pathlib.Path(__file__).with_name('codex-native-template.jsonl').read_text()
t = str(uuid.uuid4())
template = template.replace('11111111-1111-4111-8111-111111111111', 'wrong' if mode=='wrong' else n).replace('22222222-2222-4222-8222-222222222222',t).replace('/synthetic/codex-workspace',str(cwd))
records = [json.loads(line) for line in template.splitlines()]
usage = {'input_tokens':11,'cached_input_tokens':3,'cache_write_input_tokens':0,'output_tokens':4,'reasoning_output_tokens':0}
for record in records:
    payload = record['payload']
    if record['type'] == 'response_item' and payload.get('role') == 'user': payload['content'][0]['text'] = prompt.decode('utf-8')
    if record['type'] == 'response_item' and payload.get('role') == 'assistant': payload['content'][0]['text'] = 'answer:'+prompt.decode('utf-8')
    if payload.get('type') == 'task_complete': payload['last_agent_message'] = 'answer:'+prompt.decode('utf-8')
    if payload.get('type') == 'token_count':
        payload['info']['total_token_usage'] = dict(usage,total_tokens=15)
        payload['info']['last_token_usage'] = dict(usage,total_tokens=15)
    if record['type'] == 'token_usage_record':
        for key in ['usage','thread_token_usage','turn_token_usage']: payload[key] = dict(usage,total_tokens=15)
native = ''.join(json.dumps(record)+'\n' for record in records)
if mode != 'missing': source.write_text(native.rstrip('\n') if mode=='incomplete' else native)
if mode == 'eof': sys.exit(0)
if mode == 'malformed': print('{bad}',flush=True)
else: emit({'type':'turn.failed' if mode=='failed' else 'turn.completed','usage':usage})
