import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).parents[1]))
import analyze_fresh as mapping
import run_codex_candidate as guard


class LiveAdmissionTest(unittest.TestCase):
    def fixture(self, root):
        app = root / 'application'; app.mkdir()
        manifest = root / 'manifest.json'
        manifest.write_text(json.dumps({'kind': 'v3-codex-first-launch/1', 'mode': 'FIXTURE',
            'sourceCommit': 'source', 'runId': 'run', 'outputDirectory': str(root)}))
        (root / 'candidate-reservation.json').write_text(json.dumps({
            'manifestSha256': mapping.sha(manifest), 'runId': 'run'}))
        (app / 'launch.json').write_text(json.dumps({'sourceCommit': 'source', 'identity': {'runId': 'run'}}))
        observed = {'kind': 'act/canonical-inputs', 'version': '1', 'inputs': [{
            'run_id': 'run', 'journal_run_uid': 'original-execution',
            'domain': {'passed': True, 'outcome': 'PASS', 'line_coverage': 94.93, 'cost_usd': 12.0},
            'judge_details': [{'judge_name': 'final_jury', 'status': 'PASS'}]}]}
        return app, manifest, observed

    def test_fixture_success_coverage_and_even_reported_cost_contribute_nothing_live(self):
        with tempfile.TemporaryDirectory() as name:
            app, manifest, observed = self.fixture(Path(name))
            result = mapping.admit_live(observed, manifest, app)
            self.assertEqual([], result['inputs'])
            summary = result['liveAdmission']
            self.assertEqual((0, 0, 0), (summary['trials'], summary['successes'], summary['spendContributionUsd']))
            self.assertIsNone(summary['lineCoverage']); self.assertIsNone(summary['J'])
            self.assertIsNone(summary['measuredCostUsd'])
            self.assertEqual(94.93, observed['inputs'][0]['domain']['line_coverage'])

    def test_missing_relabelled_and_foreign_provenance_cannot_admit(self):
        with tempfile.TemporaryDirectory() as name:
            app, manifest, observed = self.fixture(Path(name))
            with self.assertRaisesRegex(ValueError, 'Exact launch manifest'):
                mapping.admit_live(observed, None, app)
            data = json.loads(manifest.read_text()); data['mode'] = 'NATIVE'
            manifest.write_text(json.dumps(data))
            with self.assertRaisesRegex(ValueError, 'provenance differs'):
                mapping.admit_live(observed, manifest, app)
            (app.parent / 'candidate-reservation.json').write_text(json.dumps({
                'manifestSha256': mapping.sha(manifest), 'runId': 'run'}))
            data['files'] = [{'path': str(Path(mapping.__file__).with_name('run_codex_candidate.py')), 'sha256': mapping.sha(Path(mapping.__file__).with_name('run_codex_candidate.py'))}]
            manifest.write_text(json.dumps(data))
            (app.parent / 'candidate-reservation.json').write_text(json.dumps({'manifestSha256': mapping.sha(manifest), 'runId': 'run'}))
            with self.assertRaisesRegex(ValueError, 'Fixture-free'):
                mapping.admit_live(observed, manifest, app)
            data['mode'] = 'UNKNOWN'; manifest.write_text(json.dumps(data))
            with self.assertRaisesRegex(ValueError, 'ambiguous'):
                mapping.admit_live(observed, manifest, app)
            data['mode'] = 'FIXTURE'; manifest.write_text(json.dumps(data))
            (app.parent / 'candidate-reservation.json').write_text(json.dumps({
                'manifestSha256': mapping.sha(manifest), 'runId': 'run'}))
            observed['inputs'][0]['run_id'] = 'other'
            with self.assertRaisesRegex(ValueError, 'association differs'):
                mapping.admit_live(observed, manifest, app)

    def test_native_readback_uses_original_expiry_and_rejects_synthetic_model(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name); app, manifest, observed = self.fixture(root)
            script = Path(mapping.__file__).with_name('run_codex_candidate.py').resolve()
            executable = root / 'native-wrapper'; executable.write_text('native wrapper control')
            data = {'kind': 'v3-codex-first-launch/1', 'mode': 'NATIVE', 'sourceCommit': 'source',
                    'runId': 'run', 'outputDirectory': str(root), 'policy': 'FINAL_INVENTORY_TEST_PATCH',
                    'buildBoundary': 'BWRAP_ISOLATED', 'nativeInputPolicy': 'NO_SYNTHETIC_FIXTURE_ASSETS',
                    'model': 'gpt-6-luna', 'codexExecutable': str(executable), 'runtimeClasspath': [],
                    'attemptLedger': str(root / 'ledger'),
                    'files': [{'path': str(p), 'sha256': mapping.sha(p)} for p in (script, executable)]}
            manifest.write_text(json.dumps(data)); digest = mapping.sha(manifest)
            activation = root / 'owner-activation.json'
            activation.write_text(json.dumps({'nativeAuthorization': True, 'approvedSubmissions': 1,
                'manifestSha256': digest, 'outputDirectory': str(root), 'runId': 'run',
                'ownerReference': 'STRUCTURAL_CONTROL_NOT_LIVE', 'expiresEpochSeconds': 200,
                'acceptedPolicy': data['policy'], 'acceptedBuildBoundary': data['buildBoundary']}))
            (root / 'candidate-reservation.json').write_text(json.dumps({'manifestSha256': digest,
                'runId': 'run', 'reservedAt': 100, 'activationSha256': mapping.sha(activation)}))
            (root / 'ledger.claimed').write_text(json.dumps({'manifestSha256': digest, 'runId': 'run', 'claimedAt': 110}))
            launch = json.loads((app / 'launch.json').read_text()); launch['inputs'] = data['files']
            (app / 'launch.json').write_text(json.dumps(launch))
            source = root / 'original.json'
            original = {'acquisition_uid': 'original-execution', 'process': {
                'argv': [str(executable), '--model', 'gpt-6-luna'], 'cwd': str(app / 'workspace')}}
            source.write_text(json.dumps(original))
            reference = app / 'codex-original-acquisition-reference.json'
            reference.write_text(json.dumps({'path': str(source), 'sha256': mapping.sha(source)}))
            admitted = mapping.admit_live(observed, manifest, app)
            self.assertEqual('ADMITTED_NATIVE', admitted['liveAdmission']['status'])
            self.assertIsNone(admitted['liveAdmission']['spendContributionUsd'])
            with self.assertRaisesRegex(ValueError, 'expired'):
                guard.authorize(data, digest, json.loads(activation.read_text()))
            original['process']['argv'][-1] = 'synthetic-model'
            source.write_text(json.dumps(original))
            reference.write_text(json.dumps({'path': str(source), 'sha256': mapping.sha(source)}))
            with self.assertRaisesRegex(ValueError, 'Native executable/model/execution'):
                mapping.admit_live(observed, manifest, app)

    def test_packaged_or_external_canned_patch_refuses_native_inputs(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name); jar = root / 'application.jar'
            with zipfile.ZipFile(jar, 'w') as out:
                out.writestr('codex-petclinic-tests/java/CannedTests.java', 'test corpus')
            data = {'nativeInputPolicy': 'NO_SYNTHETIC_FIXTURE_ASSETS',
                    'files': [{'path': str(jar), 'sha256': mapping.sha(jar)}]}
            with self.assertRaisesRegex(ValueError, 'native JAR'):
                guard.require_fixture_free(data)
            child = root / 'codex-child.py'; child.write_text('fixture')
            data['files'] = [{'path': str(child), 'sha256': mapping.sha(child)}]
            with self.assertRaisesRegex(ValueError, 'native input'):
                guard.require_fixture_free(data)
            with zipfile.ZipFile(jar, 'w') as out:
                out.writestr('Native.class', b'native-placeholder')
            data['files'] = [{'path': str(jar), 'sha256': mapping.sha(jar)}]
            guard.require_fixture_free(data)


if __name__ == '__main__':
    unittest.main()
