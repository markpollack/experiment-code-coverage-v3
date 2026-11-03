import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('native_launch', Path(__file__).parents[1] / 'run_native_validation.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class NativeLaunchTest(unittest.TestCase):
    def test_codex_mode_cannot_claim_claude_policy_or_native_authority(self):
        with tempfile.TemporaryDirectory() as name:
            manifest = Path(name) / 'manifest.json'
            data = {'kind': 'v3-native-coverage-launch/1', 'mode': 'NATIVE',
                    'provider': 'CODEX', 'prelaunchReview': {'status': 'ACCEPTED'}}
            manifest.write_text(json.dumps(data))
            with self.assertRaisesRegex(ValueError, 'Codex native V3 policy is unqualified'):
                module.verified_manifest(manifest)
            data['mode'] = 'SYNTHETIC_FIXTURE'
            manifest.write_text(json.dumps(data))
            with self.assertRaisesRegex(ValueError, 'Explicit Codex local fixture mode'):
                module.verified_manifest(manifest)

    def test_reviewed_cap_alone_does_not_authorize_dollars_or_more_submissions(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            ledger = root / 'ledger.json'
            ledger.write_text(json.dumps({'attempts': [{'runId': 'already-counted'}]}))
            manifest = root / 'manifest.json'
            data = {'kind': 'v3-native-coverage-launch/1', 'mode': 'NATIVE',
                    'prelaunchReview': 'technical-review', 'attemptLedger': str(ledger)}
            manifest.write_text(json.dumps(data))
            with self.assertRaisesRegex(ValueError, 'Owner dollar approval'):
                module.verified_manifest(manifest)
            data['ownerSpendApproval'] = {'reference': 'explicit-owner-grant', 'maxUsdPerAttempt': 5,
                                          'maxCoverageAttempts': 1}
            manifest.write_text(json.dumps(data))
            with self.assertRaisesRegex(ValueError, 'allowance is exhausted'):
                module.verified_manifest(manifest)
            ledger.write_text(json.dumps({'attempts': []}))
            with self.assertRaisesRegex(ValueError, 'technical review has not accepted'):
                module.verified_manifest(manifest)


if __name__ == '__main__':
    unittest.main()
