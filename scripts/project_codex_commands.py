"""Fail-closed projection of the observed Codex 0.166 single-literal exec subset.

This checks original capture hashes and occurrence populations. Independent Journal
source/assembly acceptance is still required; arbitrary JavaScript is never evaluated.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex

GRAMMAR = re.compile(r'const r = await tools\.exec_command\((\{[^\n]*\})\);\s*text\(r\.output\);\s*(?:text\(`exit_code=\$\{r\.exit_code\}`\);\s*)?\Z')


def sha(data):
    return hashlib.sha256(data).hexdigest()


def require(value, message):
    if not value:
        raise ValueError(message)


def project(manifest_path, expected_workspace=None):
    manifest_path = Path(manifest_path).resolve(strict=True)
    raw = manifest_path.read_bytes()
    manifest = json.loads(raw)
    require(manifest.get('profile_id') == 'codex-cli/0.166.0/exec-stdio+rollout'
            and manifest.get('complete') is True and manifest.get('capture_errors') == [], 'Unsupported/incomplete acquisition')
    process = manifest['process']
    require(all(process.get(key) is True for key in ('sealed', 'fresh_session', 'observed_resources_terminated',
            'stdin_closed', 'stdout_natural_eof', 'stderr_natural_eof')) and process.get('overflow') is False,
            'Incomplete capture/cleanup boundary')
    workspace = process['cwd']
    require(expected_workspace is None or workspace == expected_workspace, 'Foreign acquisition workspace')
    require(manifest['run']['launch_settings']['working_directory'] == workspace, 'Launch cwd mismatch')
    require(len(manifest['turns']) == 1, 'Only one original turn is supported')
    native_id = manifest['run']['session_uid']
    require(manifest['run']['run_uid'] == manifest['acquisition_uid'], 'Execution identity mismatch')
    sources = {}
    refs = []
    for source in manifest['sources']:
        require(source['role'] not in sources, 'Duplicate source role')
        relative = Path(source['path'])
        require(not relative.is_absolute() and '..' not in relative.parts, 'Unconfined source')
        file = manifest_path.parent / relative
        require(file.resolve(strict=True) == file, 'Symlinked source')
        data = file.read_bytes()
        require(sha(data) == source['sha256'] and len(data) == source['bytes'] == source['observed']
                and source['dropped'] == 0 and source['end'] == 'EOF'
                and source['acquisition'] == manifest['acquisition_uid'], 'Changed/incomplete original source')
        sources[source['role']] = data
        refs.append({'path': str(file), 'sha256': sha(data)})
    require(set(sources) == {'stdin', 'stream', 'stderr', 'transcript'}, 'Exact original source population required')
    stream = [json.loads(line) for line in sources['stream'].splitlines()]
    native = [json.loads(line) for line in sources['transcript'].splitlines()]
    require([x['thread_id'] for x in stream if x.get('type') == 'thread.started'] == [native_id], 'Thread identity mismatch')
    require(sum(x.get('type') == 'turn.started' for x in stream) == 1
            and sum(x.get('type') == 'turn.completed' for x in stream) == 1, 'Single completed turn required')
    contexts = [x['payload'] for x in native if x.get('type') == 'turn_context']
    require(contexts and all(x.get('cwd') == workspace and x.get('turn_id') == manifest['turns'][0]['native_turn_uid'] for x in contexts), 'Missing/foreign effective cwd context')
    calls, outputs = [], {}
    for index, record in enumerate(native, 1):
        if record.get('type') != 'response_item':
            continue
        item = record['payload']; kind = item.get('type', '')
        if kind == 'custom_tool_call':
            require(item.get('name') == 'exec' and item.get('status') == 'completed', 'Unsupported native tool family/status')
            match = GRAMMAR.fullmatch(item.get('input', ''))
            require(match is not None, 'Unsupported code-mode expression; arbitrary JavaScript is refused')
            # Strict literal object with only known scalar fields; no evaluation or dynamic expressions.
            object_text = re.sub(r'([,{]\s*)(cmd|workdir|max_output_tokens|yield_time_ms)\s*:', r'\1"\2":', match.group(1))
            args = json.loads(object_text)
            require(set(args) <= {'cmd', 'workdir', 'max_output_tokens', 'yield_time_ms'} and isinstance(args.get('cmd'), str), 'Unsupported exec arguments')
            require(args.get('workdir', workspace) == workspace, 'Foreign operation cwd')
            for key in ('max_output_tokens', 'yield_time_ms'):
                require(key not in args or type(args[key]) is int and args[key] >= 0, 'Nonliteral exec control')
            calls.append({'record': index, 'callId': item['call_id'], 'command': args['cmd'], 'cwd': workspace})
        elif kind == 'custom_tool_call_output':
            require(item['call_id'] not in outputs, 'Duplicate native tool output')
            outputs[item['call_id']] = index
        elif kind not in ('message', 'reasoning', 'additional_tools'):
            raise ValueError('Unsupported native call/output population')
    require(len({x['callId'] for x in calls}) == len(calls) and set(outputs) == {x['callId'] for x in calls}, 'Missing/duplicate/foreign native output')
    completed, active = [], None
    for index, record in enumerate(stream, 1):
        item = record.get('item', {})
        if item.get('type') not in (None, 'agent_message', 'reasoning', 'command_execution'):
            raise ValueError('Unsupported stream operation family')
        if item.get('type') != 'command_execution':
            continue
        if record['type'] == 'item.started':
            require(active is None and item.get('status') == 'in_progress', 'Parallel/ambiguous operation is unsupported')
            active = item
        elif record['type'] == 'item.completed':
            require(active is not None and item['id'] == active['id'] and item['command'] == active['command'], 'Unmatched command completion')
            require(type(item.get('exit_code')) is int and item.get('status') in ('completed', 'failed'), 'Missing command exit/status')
            require((item['exit_code'] == 0) == (item['status'] == 'completed'), 'Conflicting exit/status')
            completed.append((index, item));active = None
        else:
            raise ValueError('Unsupported command lifecycle event')
    require(active is None and len(completed) == len(calls), 'Native/stream operation population differs')
    operations = []
    for position, (call, (stream_record, item)) in enumerate(zip(calls, completed)):
        require(outputs[call['callId']] > call['record'] and (position == len(calls)-1 or outputs[call['callId']] < calls[position+1]['record']), 'Overlapping native call order')
        argv = shlex.split(item['command'])
        require(len(argv) == 3 and argv[0] in ('/bin/bash', '/usr/bin/bash') and argv[1] in ('-lc', '-c') and argv[2] == call['command'], 'Native literal/actual command mismatch')
        operations.append({'tool': 'exec_command', 'command': call['command'], 'cwd': workspace, 'background': False,
            'exitCode': item['exit_code'], 'status': item['status'], 'complete': True,
            'sourceId': f"transcript:{call['record']}:{call['callId']}", 'nativeOutputRecord': outputs[call['callId']],
            'streamRecord': stream_record, 'streamOperationId': item['id'], 'combinedOutputSha256': sha(item['aggregated_output'].encode())})
    return {'kind': 'v3-command-observations/1', 'complete': True, 'workspace': workspace,
        'sourceSha256': sha(raw), 'sourceManifest': str(manifest_path), 'sourceFiles': refs,
        'executionId': manifest['acquisition_uid'], 'nativeSessionId': native_id, 'operations': operations,
        'projection': 'codex-0.166-single-literal-exec/1', 'sourceBindingVerified': True,
        'independentArchiveRequired': True, 'nativeAcceptance': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', required=True, type=Path)
    parser.add_argument('--workspace', required=True)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    value = project(args.manifest, args.workspace)
    with args.output.open('x') as file:
        json.dump(value, file, indent=2)


if __name__ == '__main__':
    main()
