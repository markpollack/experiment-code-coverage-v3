"""Neutral selected-SDK boundary regressions; no provider execution."""
import importlib.util
from pathlib import Path
import unittest

spec=importlib.util.spec_from_file_location('policy',Path(__file__).parents[1]/'codex_first_policy.py')
policy=importlib.util.module_from_spec(spec);spec.loader.exec_module(policy)

class CompositionTest(unittest.TestCase):
    def setUp(self):
        self.argv=['--no-daemon','--ask-for-approval','never','--sandbox','workspace-write',
                   '-C','/tmp/neutral-subject','exec','--ignore-user-config','--model','gpt-6-luna',
                   '-c','model_reasoning_effort="high"','--skip-git-repo-check','--json','--','-']

    def test_selected_sdk_default_is_preserved_once_with_exact_policy(self):
        result=policy.native_arguments(self.argv,'/tmp/neutral-subject','/tmp/neutral-cache')
        self.assertEqual(1,result.count('--ignore-user-config'))
        self.assertEqual(1,result.count('--ignore-rules'))
        self.assertNotIn('--sandbox',result)
        self.assertEqual(self.argv[self.argv.index('--ignore-user-config'):],result[result.index('--ignore-user-config'):])
        self.assertIn('forced_login_method="chatgpt"',result)
        self.assertIn('default_permissions="v3_first"',result)
        self.assertIn('permissions.v3_first.network.enabled=false',result)
        self.assertIn('permissions.v3_first.filesystem={"/"="read","/tmp/neutral-subject"="write","/tmp/neutral-cache"="write"}',result)

    def test_duplicate_missing_conflicting_and_transport_inputs_refuse(self):
        cases=[self.argv[:9]+['--ignore-user-config']+self.argv[9:],
               [x for x in self.argv if x!='--ignore-user-config'],
               self.argv[:-2]+['-c','forced_login_method="api"']+self.argv[-2:],
               self.argv[:-2]+['--config','default_permissions="other"']+self.argv[-2:],
               self.argv[:-2]+['--ignore-rules']+self.argv[-2:],
               self.argv[:-2]+['--add-dir','/tmp/extra']+self.argv[-2:],
               self.argv[:-2]+['--dangerously-bypass-approvals-and-sandbox']+self.argv[-2:],
               self.argv[:-1]+['a literal task'],self.argv[:-2],
               [x.replace('workspace-write','danger-full-access') for x in self.argv],
               [x.replace('gpt-6-luna','other-model') for x in self.argv],
               [x.replace('"high"','"low"') for x in self.argv],
               [x.replace('/tmp/neutral-subject','/tmp/other') for x in self.argv],
               self.argv+['resume','old-thread']]
        for argv in cases:
            with self.subTest(argv=argv),self.assertRaisesRegex(ValueError,'Changed exact SDK invocation'):
                policy.native_arguments(argv,'/tmp/neutral-subject','/tmp/neutral-cache')

    def test_non_exec_routes_refuse(self):
        for argv in ([],['resume','old-thread'],['login'],['app-server'],['--help']):
            with self.assertRaises(ValueError):
                policy.native_arguments(argv,'/tmp/neutral-subject','/tmp/neutral-cache')

    def test_non_generation_preflight_arguments_are_unchanged(self):
        for argv in (['--version'],['login','status']):
            self.assertEqual(argv,policy.native_arguments(argv,'/tmp/neutral-subject','/tmp/neutral-cache'))
