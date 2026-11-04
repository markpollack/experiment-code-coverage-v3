import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import time
import unittest

def load(name):
    spec=importlib.util.spec_from_file_location(name,Path(__file__).parents[1]/(name+'.py'));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
surface=load('codex_candidate_surface');guard=load('run_codex_candidate');policy=load('codex_first_policy')

class FirstLiveTest(unittest.TestCase):
    def test_native_wrapper_requires_one_workspace_request_and_selects_exact_profile(self):
        args=policy.native_arguments(['--no-daemon','--ask-for-approval','never','--sandbox','workspace-write','-C','/tmp/subject','exec','--ignore-user-config','--model','gpt-6-luna','-c','model_reasoning_effort="high"','--skip-git-repo-check','--json','--','-'],'/tmp/subject','/tmp/cache')
        self.assertNotIn('--sandbox',args)
        self.assertIn('default_permissions="v3_first"',args)
        self.assertEqual(1,args.count('--ignore-user-config'))
        self.assertIn('permissions.v3_first.filesystem={"/"="read","/tmp/subject"="write","/tmp/cache"="write"}',args)
        for invalid in (['exec','prompt'],['exec','--sandbox','danger-full-access','prompt'],['exec','--sandbox','workspace-write','resume','id']):
            with self.assertRaises(ValueError):policy.native_arguments(invalid,'/tmp/subject','/tmp/cache')

    def test_root_mode_and_symbolic_root_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)/'subject';root.mkdir();before=surface.inventory(root)
            root.chmod(0o700)
            with self.assertRaisesRegex(ValueError,'root mode'):surface.validate(before,surface.inventory(root))
            link=Path(d)/'link';link.symlink_to(root)
            with self.assertRaisesRegex(ValueError,'Symbolic inventory root'):surface.inventory(link)
            file=Path(d)/'file';file.write_text('not a directory')
            with self.assertRaisesRegex(ValueError,'Ordinary directory'):surface.inventory(file)

    def test_final_inventory_accepts_patch_without_shell_interpretation(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);(root/'src/test').mkdir(parents=True);(root/'pom.xml').write_text('protected')
            before=surface.inventory(root);(root/'src/test/Test.java').write_text('test')
            (root/'target/classes').mkdir(parents=True);(root/'target/classes/A.class').write_bytes(b'output')
            result=surface.validate(before,surface.inventory(root))
            self.assertEqual(['src/test/Test.java'],[x['path'] for x in result['changes']])
            (root/'unknown').write_text('protected untracked')
            with self.assertRaisesRegex(ValueError,'Protected'):surface.validate(before,surface.inventory(root))
    def test_generated_scope_types_modes_symlinks_and_deletions(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);(root/'src/test').mkdir(parents=True);(root/'src/test/T.java').write_text('x')
            (root/'protected').write_text('x');before=surface.inventory(root)
            (root/'src/test/T.java').unlink();self.assertEqual(1,len(surface.validate(before,surface.inventory(root))['changes']))
            (root/'target').mkdir();(root/'target/arbitrary.sh').write_text('bad')
            with self.assertRaisesRegex(ValueError,'Protected'):surface.validate(before,surface.inventory(root))
            (root/'target/arbitrary.sh').unlink();(root/'target/classes').symlink_to('/tmp')
            with self.assertRaisesRegex(ValueError,'Symlink'):surface.validate(before,surface.inventory(root))
            (root/'target/classes').unlink();(root/'protected').chmod(0o755)
            with self.assertRaisesRegex(ValueError,'mode'):surface.validate(before,surface.inventory(root))
            (root/'protected').unlink();(root/'protected').mkdir()
            with self.assertRaisesRegex(ValueError,'Type'):surface.validate(before,surface.inventory(root))
    def test_missing_mismatch_expired_policy_approval_and_exclusive_reservation(self):
        with tempfile.TemporaryDirectory() as d:
            data={'outputDirectory':d+'/out','runId':'run','policy':'FINAL_INVENTORY_TEST_PATCH','buildBoundary':'BWRAP_ISOLATED','attemptLedger':d+'/ledger'}
            approval={'nativeAuthorization':True,'approvedSubmissions':1,'manifestSha256':'hash','outputDirectory':data['outputDirectory'],'runId':'run','ownerReference':'fixture','expiresEpochSeconds':time.time()+60,'acceptedPolicy':data['policy'],'acceptedBuildBoundary':'BWRAP_ISOLATED'}
            guard.authorize(data,'hash',approval)
            for a in (None,dict(approval,manifestSha256='wrong'),dict(approval,outputDirectory='wrong'),dict(approval,expiresEpochSeconds=0),dict(approval,acceptedBuildBoundary='UNKNOWN')):
                with self.assertRaises(ValueError):guard.authorize(data,'hash',a)
            guard.reserve(data,'hash')
            with self.assertRaises(FileExistsError):guard.reserve(data,'hash')
