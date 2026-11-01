import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('analyze_fresh', Path(__file__).parents[1] / 'analyze_fresh.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class MappingTest(unittest.TestCase):
    def test_rejects_wrong_full_identity_before_analysis(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            result = root / 'result.json'
            result.write_text(json.dumps({'recordingSchemaVersion': 3, 'kind': 'AGENT_CANONICAL',
                'experimentId': 'run', 'items': [{'identity': {'runId': 'run'},
                'invocations': [{'attempt': {'row': {'runId': 'other'}}}]}]}))
            with self.assertRaisesRegex(ValueError, 'association'):
                module.mapping(result, root / 'export', root / 'receipt', root, 1)

    def test_changed_export_refuses_without_importing_act(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            result = root / 'result.json'
            identity = {'runId': 'run'}
            result.write_text(json.dumps({'recordingSchemaVersion': 3, 'kind': 'AGENT_CANONICAL',
                'experimentId': 'run', 'items': [{'identity': identity,
                'invocations': [{'attempt': {'row': identity, 'invocationId': 'invoke'}}]}]}))
            receipt = root / 'receipt.json'
            receipt.write_text(json.dumps({'verifier_exit': 0, 'invocation_id': 'invoke', 'export_sha256': 'wrong'}))
            export = root / 'export'; export.write_text('changed')
            with self.assertRaisesRegex(ValueError, 'Changed canonical export'):
                module.mapping(result, export, receipt, root, 1)


if __name__ == '__main__':
    unittest.main()
