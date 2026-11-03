import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('projection', Path(__file__).parents[1] / 'project_codex_commands.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class ProjectionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.workspace = str(self.root / 'workspace')
        self.native = [
            {'type': 'turn_context', 'payload': {'cwd': self.workspace, 'turn_id': 'turn'}},
            {'type': 'response_item', 'payload': {'type': 'custom_tool_call', 'name': 'exec', 'status': 'completed',
             'call_id': 'call-1', 'input': 'const r = await tools.exec_command({cmd:"cat ./file", max_output_tokens:200});\ntext(r.output);\n'}},
            {'type': 'response_item', 'payload': {'type': 'custom_tool_call_output', 'call_id': 'call-1'}}]
        self.stream = [{'type': 'thread.started', 'thread_id': 'native'}, {'type': 'turn.started'},
            {'type': 'item.started', 'item': {'type': 'command_execution', 'id': 'item-1',
             'command': "/bin/bash -lc 'cat ./file'", 'status': 'in_progress'}},
            {'type': 'item.completed', 'item': {'type': 'command_execution', 'id': 'item-1',
             'command': "/bin/bash -lc 'cat ./file'", 'status': 'completed', 'exit_code': 0, 'aggregated_output': 'same\n'}},
            {'type': 'turn.completed'}]
        self.manifest = {'profile_id': 'codex-cli/0.166.0/exec-stdio+rollout', 'complete': True, 'capture_errors': [],
            'acquisition_uid': 'execution', 'process': {'cwd': self.workspace, 'sealed': True, 'fresh_session': True,
            'observed_resources_terminated': True, 'stdin_closed': True, 'stdout_natural_eof': True,
            'stderr_natural_eof': True, 'overflow': False}, 'run': {'run_uid': 'execution', 'session_uid': 'native',
            'launch_settings': {'working_directory': self.workspace}}, 'turns': [{'native_turn_uid': 'turn'}]}
        self.write()

    def write(self):
        sources = {'stdin': b'prompt', 'stderr': b'', 'stream': b''.join(json.dumps(x).encode()+b'\n' for x in self.stream),
                   'transcript': b''.join(json.dumps(x).encode()+b'\n' for x in self.native)}
        refs = []
        for role, data in sources.items():
            path = role + '.bin'
            (self.root / path).write_bytes(data)
            refs.append({'role': role, 'path': path, 'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data),
                         'observed': len(data), 'dropped': 0, 'end': 'EOF', 'acquisition': 'execution'})
        self.manifest['sources'] = refs
        self.path = self.root / 'acquisition.json'
        self.path.write_text(json.dumps(self.manifest))

    def test_exact_observed_literal_grammar_binds_both_populations(self):
        result = m.project(self.path, self.workspace)
        self.assertTrue(result['sourceBindingVerified'])
        self.assertFalse(result['nativeAcceptance'])
        self.assertEqual(1, len(result['operations']))
        self.assertEqual('cat ./file', result['operations'][0]['command'])
        self.assertEqual(3, result['operations'][0]['nativeOutputRecord'])
        self.assertEqual(4, result['operations'][0]['streamRecord'])

    def test_modified_bytes_without_manifest_reseal_rejected(self):
        (self.root / 'transcript.bin').write_text('{}\n')
        with self.assertRaisesRegex(ValueError, 'Changed/incomplete'):
            m.project(self.path)

    def test_missing_output_and_omitted_stream_operation_rejected(self):
        self.native.pop()
        self.write()
        with self.assertRaisesRegex(ValueError, 'native output'):
            m.project(self.path)
        self.setUp()
        self.stream = [x for x in self.stream if not x.get('item')]
        self.write()
        with self.assertRaisesRegex(ValueError, 'population differs'):
            m.project(self.path)

    def test_foreign_context_or_explicit_operation_cwd_rejected(self):
        self.native[0]['payload']['cwd'] = '/foreign'
        self.write()
        with self.assertRaisesRegex(ValueError, 'effective cwd'):
            m.project(self.path)
        self.setUp()
        self.native[1]['payload']['input'] = 'const r = await tools.exec_command({cmd:"cat ./file",workdir:"/foreign"});\ntext(r.output);\n'
        self.write()
        with self.assertRaisesRegex(ValueError, 'operation cwd'):
            m.project(self.path)

    def test_arbitrary_javascript_and_unknown_native_tools_fail_closed(self):
        self.native[1]['payload']['input'] += 'await tools.exec_command({cmd:"hidden"});'
        self.write()
        with self.assertRaisesRegex(ValueError, 'arbitrary JavaScript'):
            m.project(self.path)
        self.setUp()
        self.native[1]['payload']['name'] = 'apply_patch'
        self.write()
        with self.assertRaisesRegex(ValueError, 'tool family'):
            m.project(self.path)

    def test_actual_command_mismatch_and_incomplete_capture_rejected(self):
        for record in self.stream:
            if record.get('item'):
                record['item']['command'] = "/bin/bash -lc 'touch hidden'"
        self.write()
        with self.assertRaisesRegex(ValueError, 'command mismatch'):
            m.project(self.path)
        self.manifest['process']['stdout_natural_eof'] = False
        self.write()
        with self.assertRaisesRegex(ValueError, 'capture/cleanup'):
            m.project(self.path)


if __name__ == '__main__':
    unittest.main()
