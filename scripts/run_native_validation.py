"""Bound one reviewed native coverage pipeline; never retries a subject or uses the legacy runner."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time

SOFT_SECONDS = 1785
HARD_GRACE_SECONDS = 15


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def write(path, value):
    with path.open('x') as stream:
        json.dump(value, stream, indent=2)


def verified_manifest(path):
    data = json.loads(path.read_text())
    if data.get('kind') == 'v3-codex-first-launch/1':
        import run_codex_candidate
        run_codex_candidate.validate(data)
        reservation = json.loads((Path(data['outputDirectory']) / 'candidate-reservation.json').read_text())
        if reservation.get('manifestSha256') != sha(path) or reservation.get('runId') != data['runId']:
            raise ValueError('Exact guard reservation required')
        data['candidateMode'] = 'fixture' if data['mode'] == 'FIXTURE' else 'native'
        data['mode'] = 'CODEX_CANDIDATE'
        return data
    if data.get('kind') != 'v3-native-coverage-launch/1' or not data.get('prelaunchReview'):
        raise ValueError('Named coordinator prelaunch review is required')
    if data.get('mode') not in ('NATIVE', 'SYNTHETIC_FIXTURE'):
        raise ValueError('Explicit native or synthetic-fixture launch mode required')
    provider = data.get('provider', 'CLAUDE')
    if provider not in ('CLAUDE', 'CODEX'):
        raise ValueError('Explicit supported provider required')
    if provider == 'CODEX':
        if data['mode'] != 'SYNTHETIC_FIXTURE':
            raise ValueError('Codex native V3 policy is unqualified: test-only writes and exact Maven command enforcement required')
        if data.get('fixtureMode') not in ('success', 'failed', 'wrong'):
            raise ValueError('Explicit Codex local fixture mode required')
    if data['mode'] == 'NATIVE':
        approval = data.get('ownerSpendApproval') or {}
        if (not approval.get('reference') or approval.get('maxUsdPerAttempt', 0) < 5
                or not 1 <= approval.get('maxCoverageAttempts', 0) <= 6):
            raise ValueError('Actual Owner dollar approval must cover this USD 5 capped launch')
        ledger = json.loads(Path(data['attemptLedger']).read_text())
        if len(ledger['attempts']) >= approval['maxCoverageAttempts']:
            raise ValueError('Owner-approved cumulative coverage submission allowance is exhausted')
        review = data.get('prelaunchReview')
        if not isinstance(review, dict) or review.get('status') != 'ACCEPTED' or not review.get('reference'):
            raise ValueError('Named coordinator technical review has not accepted this launch')
    selected = {}
    for ref in data['files']:
        file = Path(ref['path']).resolve(strict=True)
        if sha(file) != ref['sha256']:
            raise ValueError('Changed reviewed launch artifact: ' + str(file))
        selected[str(file)] = ref['sha256']
    for key in ('runtimeClasspath', 'readerClasspath'):
        for path in data[key]:
            if str(Path(path).resolve(strict=True)) not in selected:
                raise ValueError('Unhashed classpath entry: ' + path)
    executable_key = 'codexExecutable' if provider == 'CODEX' else 'claudeExecutable'
    for key in (executable_key, 'actPython', 'readerSource', 'mappingScript', 'javaExecutable', 'timeoutExecutable', 'launcherPython'):
        if str(Path(data[key]).resolve(strict=True)) not in selected:
            raise ValueError('Unhashed launch component: ' + key)
    if str(Path(sys.executable).resolve()) != str(Path(data['launcherPython']).resolve()):
        raise ValueError('Launch interpreter differs from reviewed binary')
    return data


def execute(argv, log, env):
    with log.open('x') as stream:
        completed = subprocess.run(argv, stdout=stream, stderr=subprocess.STDOUT, env=env, check=False)
    if completed.returncode:
        raise RuntimeError(f'Pipeline stage failed ({completed.returncode}); retained {log}')


def inside(manifest, root, run_id):
    data = verified_manifest(manifest)
    if data.get('candidateMode') and (data['runId'] != run_id or data['outputDirectory'] != str(root)):
        raise ValueError('Foreign candidate run/output')
    if data['mode'] == 'NATIVE' and data.get('approvedRunId') != run_id:
        raise ValueError('Native run identity differs from reviewed launch')
    env = os.environ.copy()
    repo = str(Path(data['mavenRepository']).resolve(strict=True))
    if not data.get('candidateMode'):
        env['MAVEN_ARGS'] = '-Dmaven.repo.local=' + repo
    app = root / 'application'
    provider_args = (['--codex-executable',data['codexExecutable'],'--codex-candidate-mode',data['candidateMode']]
                     if data.get('candidateMode') else ['--codex-executable', data['codexExecutable'], '--codex-fixture-mode', data['fixtureMode']]
                     if data.get('provider') == 'CODEX' else ['--claude-executable', data['claudeExecutable']])
    execute([data['javaExecutable'], '-Dcoverage.mavenRepo=' + repo, *(['-Dcoverage.candidatePython='+data['launcherPython'], '-Dcoverage.candidateSurface='+data['candidateSurface'], '-Dcoverage.candidateBuild=true', '-Dcoverage.buildSandbox='+data['buildSandbox'], '-Dcoverage.candidateGuard='+data['launcher'], '-Dcoverage.candidateManifest='+str(manifest)] if data.get('candidateMode') else []), '-cp', os.pathsep.join(data['runtimeClasspath']),
             'io.tuvium.experiment.coverage.NativeCoverageApp', '--project-root', data['projectRoot'],
             '--output-dir', str(app), '--run-id', run_id, '--attempt-ledger', data['attemptLedger'],
             '--outer-deadline', env['V3_SOFT_DEADLINE_EPOCH_MS']] + provider_args,
            root / 'application.log', env)
    reader = root / 'independent-reader'
    execute([data['javaExecutable'], '--class-path', os.pathsep.join(data['readerClasspath']), data['readerSource'],
             str(app / 'results'), run_id, str(reader)], root / 'independent-reader.log', env)
    # Mapping has no provider/native parser. ACT owns canonical populations and accounting.
    encoded = base64.urlsafe_b64encode(run_id.encode()).decode().rstrip('=')
    result = app / 'results' / encoded / 'run.json'
    inputs = root / 'act-inputs.json'
    execute([data['actPython'], '-I', data['mappingScript'], '--result', str(result),
             '--export', str(reader / 'canonical.jsonl'), '--reader-receipt', str(reader / 'reader-receipt.json'),
             '--evidence', str(app), '--output', str(inputs), '--mapping-only', 'true',
             '--launch-manifest', str(manifest)], root / 'mapping.log', env)
    if not json.loads(inputs.read_text())['inputs']:
        write(root / 'live-exclusion.json', json.loads(inputs.read_text())['liveAdmission'])
        raise ValueError('Synthetic fixture excluded from live ACT population')
    execute([data['actPython'], '-I', '-m', 'agent_control_theory.canonical', '--inputs', str(inputs),
             '--output', str(root / 'act')], root / 'act.log', env)
    if data.get('candidateMode'):
        execute([data['launcherPython'],data['candidateAcceptance'],'--root',str(root),'--manifest',str(manifest),'--output',str(root/'candidate-acceptance.json')],root/'candidate-acceptance.log',env)
    write(root / 'pipeline-completion.json', {'status': 'COMPLETE', 'result': str(result),
          'resultSha256': sha(result), 'exportSha256': sha(reader / 'canonical.jsonl'),
          'actManifestSha256': sha(root / 'act/manifest.json'), 'actualCostState': 'UNKNOWN',
          'accountingSource': 'Qualified scoped terminal/source/ACT evidence; cumulative observations are not summed'})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', required=True, type=Path)
    parser.add_argument('--output-dir', required=True, type=Path)
    parser.add_argument('--run-id', required=True)
    parser.add_argument('--inside', action='store_true', help=argparse.SUPPRESS)
    args = parser.parse_args()
    manifest = args.manifest.resolve(strict=True)
    root = args.output_dir.absolute()
    if args.inside:
        inside(manifest, root, args.run_id)
        return
    data = verified_manifest(manifest)
    if data['mode'] == 'NATIVE' and data.get('approvedRunId') != args.run_id:
        raise ValueError('Native run identity differs from reviewed launch')
    root.mkdir()
    write(root / 'launch-review.json', {'manifest': str(manifest), 'sha256': sha(manifest),
          'review': data['prelaunchReview'], 'runId': args.run_id, 'softSeconds': SOFT_SECONDS,
          'hardGraceSeconds': HARD_GRACE_SECONDS, 'maximumSeconds': 1800})
    env = os.environ.copy()
    env['V3_SOFT_DEADLINE_EPOCH_MS'] = str(int((time.time() + SOFT_SECONDS) * 1000))
    started = time.monotonic()
    command = [data['timeoutExecutable'], '--signal=TERM', '--kill-after=' + str(HARD_GRACE_SECONDS) + 's', str(SOFT_SECONDS) + 's',
               sys.executable, str(Path(__file__).resolve()), '--inside', '--manifest', str(manifest),
               '--output-dir', str(root), '--run-id', args.run_id]
    with (root / 'pipeline.log').open('x') as log:
        owned = subprocess.Popen(command, env=env, start_new_session=True, stdout=log, stderr=subprocess.STDOUT)
        exit_code = owned.wait()
    remaining_group = False
    try:
        os.killpg(owned.pid, 0)
        remaining_group = True
        os.killpg(owned.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    write(root / 'outer-receipt.json', {'exit': exit_code, 'elapsedSeconds': time.monotonic() - started,
          'ownedProcessGroup': owned.pid, 'remainingOwnedGroupObserved': remaining_group,
          'remainingOwnedGroupKillRequested': remaining_group,
          'scope': 'Fresh owned process group only; escaped processes are not qualified'})
    if exit_code or remaining_group:
        raise SystemExit(exit_code or 1)


if __name__ == '__main__':
    main()
