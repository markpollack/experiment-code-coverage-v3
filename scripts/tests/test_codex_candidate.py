import copy
import importlib.util
from pathlib import Path
import shutil
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('candidate', Path(__file__).parents[1] / 'verify_codex_candidate.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class CandidatePolicyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.subject = self.root / 'subject'
        (self.subject / 'src/test').mkdir(parents=True)
        (self.subject / 'src/main').mkdir()
        (self.subject / 'src/main/App.java').write_text('protected')
        (self.subject / 'src/test/ExistingTest.java').write_text('old test')
        self.verification = self.root / 'verification'
        shutil.copytree(self.subject, self.verification)
        self.before = m.inventory(self.subject)
        self.evidence = {'kind': 'v3-command-observations/1', 'complete': True,
                         'sourceSha256': 'synthetic-source-hash', 'operations': [
            {'tool': 'exec_command', 'command': m.BUILD, 'background': False,
             'exitCode': 0, 'sourceId': 'fixture/1', 'complete': True}]}

    def check(self):
        return m.validate(self.before, m.inventory(self.subject), self.evidence)

    def test_valid_new_and_changed_tests_transfer_to_fresh_copy_without_generated_output(self):
        (self.subject / 'src/test/ExistingTest.java').write_text('changed test')
        (self.subject / 'src/test/NewTest.java').write_text('new test')
        (self.subject / 'target/classes').mkdir(parents=True)
        (self.subject / 'target/classes/App.class').write_bytes(b'generated')
        after = m.inventory(self.subject)
        patch = self.check()
        self.assertEqual(2, len(patch['changes']))
        self.assertFalse(patch['nativeAcceptance'])
        self.assertIn('target/classes/App.class', patch['generatedExcluded'])
        result = m.apply_patch(self.subject, self.verification, self.before, after, patch)
        self.assertEqual('new test', (self.verification / 'src/test/NewTest.java').read_text())
        self.assertNotIn('target/classes/App.class', result['entries'])
        self.assertEqual('protected', (self.verification / 'src/main/App.java').read_text())

    def test_protected_tracked_edit_rejected(self):
        (self.subject / 'src/main/App.java').write_text('changed')
        with self.assertRaisesRegex(ValueError, 'Protected authored'):
            self.check()

    def test_untracked_protected_file_rejected(self):
        (self.subject / 'untracked.sh').write_text('echo invalid')
        with self.assertRaisesRegex(ValueError, 'Protected authored'):
            self.check()

    def test_symlink_and_mode_change_rejected(self):
        path = self.subject / 'src/test/link'
        path.symlink_to('/tmp')
        with self.assertRaisesRegex(ValueError, 'Symlink'):
            self.check()
        path.unlink()
        (self.subject / 'src/test/ExistingTest.java').chmod(0o755)
        with self.assertRaisesRegex(ValueError, 'mode changed'):
            self.check()

    def test_generated_symlink_is_not_a_generated_output_exemption(self):
        (self.subject / 'target').symlink_to('/tmp')
        with self.assertRaisesRegex(ValueError, 'Symlink'):
            self.check()

    def test_missing_or_disallowed_command_evidence_rejected(self):
        for observations in (None, {}, dict(self.evidence, complete=False)):
            with self.assertRaisesRegex(ValueError, 'command evidence'):
                m.validate(self.before, m.inventory(self.subject), observations)
        for command in ('rm -rf src/main', m.BUILD + ' &', 'cat -- ../secret', 'cat -- x; touch y'):
            evidence = copy.deepcopy(self.evidence)
            evidence['operations'][0]['command'] = command
            with self.assertRaises(ValueError):
                m.validate(self.before, m.inventory(self.subject), evidence)
        self.evidence['operations'][0]['complete'] = False
        with self.assertRaisesRegex(ValueError, 'completion evidence'):
            self.check()

    def test_test_patch_observation_rejects_protected_patch_paths(self):
        self.evidence['operations'] = [{'tool': 'apply_patch', 'sourceId': 'fixture/2',
            'complete': True, 'patch': '*** Begin Patch\n*** Add File: src/test/NewTest.java\n+test\n*** End Patch'}]
        self.check()
        self.evidence['operations'][0]['patch'] = '*** Begin Patch\n*** Add File: pom.xml\n+x\n*** End Patch'
        with self.assertRaisesRegex(ValueError, 'patch path'):
            self.check()

    def test_changed_subject_or_nonfresh_verification_cannot_be_transferred(self):
        (self.subject / 'src/test/NewTest.java').write_text('new')
        after = m.inventory(self.subject)
        patch = self.check()
        (self.verification / 'src/main/App.java').write_text('changed protected')
        with self.assertRaisesRegex(ValueError, 'verification copy differs'):
            m.apply_patch(self.subject, self.verification, self.before, after, patch)
        (self.subject / 'src/test/NewTest.java').write_text('later change')
        with self.assertRaisesRegex(ValueError, 'Subject changed'):
            m.apply_patch(self.subject, self.verification, self.before, after, patch)


if __name__ == '__main__':
    unittest.main()
