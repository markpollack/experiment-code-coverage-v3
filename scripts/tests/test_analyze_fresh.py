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
            result.write_text(json.dumps({'recordingSchemaVersion': 4, 'kind': 'AGENT_CANONICAL',
                'experimentId': 'run', 'items': [{'identity': {'runId': 'run'},
                'invocations': [{'attempt': {'row': {'runId': 'other'}}}]}]}))
            with self.assertRaisesRegex(ValueError, 'association'):
                module.mapping(result, root / 'export', root / 'receipt', root, 1)

    def test_changed_export_refuses_without_importing_act(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            result = root / 'result.json'
            identity = {'runId': 'run'}
            result.write_text(json.dumps({'recordingSchemaVersion': 4, 'kind': 'AGENT_CANONICAL',
                'experimentId': 'run', 'items': [{'identity': identity,
                'invocations': [{'attempt': {'row': identity, 'invocationId': 'invoke'}}]}]}))
            receipt = root / 'receipt.json'
            receipt.write_text(json.dumps({'verifier_exit': 0, 'invocation_id': 'invoke', 'export_sha256': 'wrong'}))
            export = root / 'export'; export.write_text('changed')
            with self.assertRaisesRegex(ValueError, 'Changed canonical export'):
                module.mapping(result, export, receipt, root, 1)

    def selected_fixture(self, root):
        evidence = root / 'assessment-r2'
        (evidence / 'judges/quality/acquired').mkdir(parents=True)
        (evidence / 'judges/quality/acquired/acquisition.json').write_text(
            json.dumps({'acquisition_uid': 'quality-r2'}))
        named = {name: {'schemaVersion': 6, 'producerStatus': 'pass',
                       'reasoning': 'Observed fixture evidence — exact text',
                       'checks': [], 'invocationIds': ['quality-r2'] if name == 't2_quality' else []}
                 for name in ('t0_build', 't1_coverage', 't2_quality')}
        for name, judgment in named.items():
            (evidence / 'judges' / (name + '.json')).write_text(json.dumps(judgment))
        producer = json.dumps({'schemaVersion': 6, 'judgment': named['t2_quality'],
            'individualByName': {'t2_quality': named['t2_quality']},
            'compositeAttempts': [{'name': name, 'verdict': {'individualByName': {name: judgment}}}
                                  for name, judgment in named.items()]}, ensure_ascii=False, indent=2)
        document = {'producerJson': producer,
                    'sha256': module.hashlib.sha256(producer.encode('utf-8')).hexdigest(),
                    'conclusion': 'PASS', 'codec': 'agent-judge-v6:0.18.0'}
        identity = {'runId': 'run', 'itemId': 'item', 'variant': 'simple', 'replicateIndex': 0}
        row = {'identity': identity, 'judgeState': 'RETURNED', 'verdicts': [document],
               'workflowState': 'SUCCEEDED', 'domain': {'measurements': [], 'artifacts': []},
               'invocations': [{'attempt': {'row': identity, 'invocationId': 'task',
                   'workflowAttemptId': 'physical', 'attemptNumber': 1}, 'elapsedMs': 12,
                   'responseState': 'SUCCEEDED', 'captureState': 'COMPLETE'}]}
        result = evidence / 'result.json'
        result.write_text(json.dumps({'recordingSchemaVersion': 4, 'kind': 'AGENT_CANONICAL',
                                     'experimentId': 'run', 'items': [row]}))
        export = root / 'export'; export.write_text('unit fixture export')
        receipt = root / 'reader.json'
        receipt.write_text(json.dumps({'verifier_exit': 0, 'invocation_id': 'task',
            'export_sha256': module.sha(export),
            'verified_files': [{'path': str(export), 'sha256': module.sha(export)}]}))
        return result, export, receipt, evidence, document

    def test_retains_full_selected_envelope_and_revision_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            result, export, receipt, evidence, document = self.selected_fixture(Path(directory))
            mapped = module.mapping(result, export, receipt, evidence, 1)['inputs'][0]
            details = mapped['judge_details']
            self.assertEqual(4, len(details))
            self.assertEqual(document, details[0]['producer_document'])
            self.assertEqual('final_jury', details[0]['judge_name'])
            for detail in details:
                self.assertEqual(module.sha(result), detail['assessment_revision_sha256'])
                self.assertEqual('task', detail['task_invocation_id'])
                self.assertEqual('quality-r2', detail['quality_acquisition']['invocation_id'])
            self.assertIsNone(mapped['domain']['t0_build'])

    def test_rejects_wrong_judgment_revision_and_changed_producer(self):
        with tempfile.TemporaryDirectory() as directory:
            result, export, receipt, evidence, document = self.selected_fixture(Path(directory))
            path = evidence / 'judges/t2_quality.json'
            original = path.read_text()
            path.write_text(original.replace('pass', 'fail'))
            with self.assertRaisesRegex(ValueError, 'selected Jury revision'):
                module.mapping(result, export, receipt, evidence, 1)
            path.write_text(original)
            data = json.loads(result.read_text())
            data['items'][0]['verdicts'][0]['producerJson'] += ' '
            result.write_text(json.dumps(data))
            with self.assertRaisesRegex(ValueError, 'producer hash'):
                module.mapping(result, export, receipt, evidence, 1)

    def test_combined_result_retains_selected_parent_and_rejects_wrong_association(self):
        with tempfile.TemporaryDirectory() as directory:
            result, export, receipt, evidence, document = self.selected_fixture(Path(directory))
            combined = Path(directory) / 'combined.json'
            data = json.loads(result.read_text())
            data['runtimeArtifacts'] = [{'subject': 'selected-assessment-result',
                'path': str(result), 'sha256': module.sha(result)}]
            combined.write_text(json.dumps(data))
            details = module.mapping(combined, export, receipt, evidence, 1)['inputs'][0]['judge_details']
            self.assertEqual(4, len(details))
            for detail in details:
                self.assertEqual(str(result), detail['assessment_revision_path'])
                self.assertEqual(module.sha(result), detail['assessment_revision_sha256'])
                self.assertEqual(str(combined), detail['combined_result_path'])
            wrong = json.loads(result.read_text())
            wrong['items'][0]['invocations'][0]['attempt']['invocationId'] = 'another-task'
            result.write_text(json.dumps(wrong))
            data['runtimeArtifacts'][0]['sha256'] = module.sha(result)
            combined.write_text(json.dumps(data))
            with self.assertRaisesRegex(ValueError, 'parent association'):
                module.mapping(combined, export, receipt, evidence, 1)

    def test_final_jury_failure_is_not_replaced_by_individual_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            result, export, receipt, evidence, document = self.selected_fixture(Path(directory))
            data = json.loads(result.read_text())
            final = data['items'][0]['verdicts'][0]
            producer = json.loads(final['producerJson'])
            producer['judgment'] = {'schemaVersion': 6, 'producerStatus': 'fail',
                                   'reasoning': 'Final Jury policy finding'}
            final['producerJson'] = json.dumps(producer)
            final['sha256'] = module.hashlib.sha256(final['producerJson'].encode()).hexdigest()
            final['conclusion'] = 'FAIL'
            result.write_text(json.dumps(data))
            domain = module.mapping(result, export, receipt, evidence, 1)['inputs'][0]['domain']
            self.assertEqual('FAIL', domain['outcome'])
            self.assertFalse(domain['passed'])

    def test_native_run_identity_uses_independently_checked_acquisition(self):
        with tempfile.TemporaryDirectory() as directory:
            result, export, receipt, evidence, _ = self.selected_fixture(Path(directory))
            source = Path(directory) / 'acquisition.json'
            source.write_text(json.dumps({'complete': True, 'acquisition_uid': 'client-execution',
                                          'run': {'run_uid': 'client-execution'}}))
            ref = {'path': str(source), 'sha256': module.sha(source), 'subject': 'acquisition-manifest'}
            data = json.loads(result.read_text()); data['items'][0]['invocations'][0]['acquisitions'] = [ref]
            data['items'][0]['invocations'][0]['attempt']['workflowRunId'] = 'workflow-run'
            result.write_text(json.dumps(data))
            accepted = json.loads(receipt.read_text()); accepted['journal_run_uid'] = 'client-execution'
            accepted['verified_files'].append(ref); receipt.write_text(json.dumps(accepted))
            mapped = module.mapping(result, export, receipt, evidence, 1)['inputs'][0]
            self.assertEqual('client-execution', mapped['journal_run_uid'])
            self.assertEqual('task', mapped['invocation_id'])
            self.assertEqual('workflow-run', mapped['execution_run_id'])
            accepted['journal_run_uid'] = 'forged'; receipt.write_text(json.dumps(accepted))
            with self.assertRaisesRegex(ValueError, 'source/run identity'):
                module.mapping(result, export, receipt, evidence, 1)

    def test_deterministic_validation_pass_preserves_semantic_not_assessed(self):
        with tempfile.TemporaryDirectory() as directory:
            result, export, receipt, evidence, _ = self.selected_fixture(Path(directory))
            (evidence / 'judges/quality/acquired/acquisition.json').unlink()
            quality = evidence / 'judges/t2_quality.json'
            integrity = quality.read_text(); quality.unlink()
            (evidence / 'judges/edit_surface.json').write_text(integrity)
            (evidence / 'judges/judging-mode.json').write_text(json.dumps({
                'mode': 'deterministic-coverage-validation/1', 'semanticQuality': 'NOT_ASSESSED',
                'judgeInferenceCalls': 0}))
            data = json.loads(result.read_text()); document = data['items'][0]['verdicts'][0]
            document['producerJson'] = document['producerJson'].replace('t2_quality', 'edit_surface')
            document['sha256'] = module.hashlib.sha256(document['producerJson'].encode()).hexdigest()
            result.write_text(json.dumps(data))
            mapped = module.mapping(result, export, receipt, evidence, 1)['inputs'][0]
            self.assertEqual('PASS', mapped['domain']['outcome'])
            self.assertTrue(mapped['domain']['passed'])
            self.assertEqual('NOT_ASSESSED', mapped['domain']['semantic_quality'])
            self.assertNotIn('t2_quality', mapped['domain'])
            self.assertEqual({'final_jury', 'edit_surface', 't0_build', 't1_coverage'},
                             {x['judge_name'] for x in mapped['judge_details']})


if __name__ == '__main__':
    unittest.main()
