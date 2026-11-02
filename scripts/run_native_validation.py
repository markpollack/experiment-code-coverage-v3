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
    if data.get('kind') != 'v3-native-coverage-launch/1' or not data.get('prelaunchReview'):
        raise ValueError('Named coordinator prelaunch review is required')
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
    for key in ('claudeExecutable', 'actPython', 'readerSource', 'mappingScript'):
        if str(Path(data[key]).resolve(strict=True)) not in selected:
            raise ValueError('Unhashed launch component: ' + key)
    return data


def execute(argv, log, env):
    with log.open('x') as stream:
        completed = subprocess.run(argv, stdout=stream, stderr=subprocess.STDOUT, env=env, check=False)
    if completed.returncode:
        raise RuntimeError(f'Pipeline stage failed ({completed.returncode}); retained {log}')


def inside(manifest, root, run_id):
    data = verified_manifest(manifest)
    env = os.environ.copy()
    repo = str(Path(data['mavenRepository']).resolve(strict=True))
    env['MAVEN_ARGS'] = '-Dmaven.repo.local=' + repo
    app = root / 'application'
    execute(['java', '-Dcoverage.mavenRepo=' + repo, '-cp', os.pathsep.join(data['runtimeClasspath']),
             'io.tuvium.experiment.coverage.NativeCoverageApp', '--project-root', data['projectRoot'],
             '--output-dir', str(app), '--run-id', run_id, '--attempt-ledger', data['attemptLedger'],
             '--claude-executable', data['claudeExecutable'], '--outer-deadline', env['V3_SOFT_DEADLINE_EPOCH_MS']],
            root / 'application.log', env)
    reader = root / 'independent-reader'
    execute(['java', '--class-path', os.pathsep.join(data['readerClasspath']), data['readerSource'],
             str(app / 'results'), run_id, str(reader)], root / 'independent-reader.log', env)
    # Mapping has no provider/native parser. ACT owns canonical populations and accounting.
    encoded = base64.urlsafe_b64encode(run_id.encode()).decode().rstrip('=')
    result = app / 'results' / encoded / 'run.json'
    inputs = root / 'act-inputs.json'
    execute([data['actPython'], '-I', data['mappingScript'], '--result', str(result),
             '--export', str(reader / 'canonical.jsonl'), '--reader-receipt', str(reader / 'reader-receipt.json'),
             '--evidence', str(app), '--output', str(inputs), '--mapping-only', 'true'], root / 'mapping.log', env)
    execute([data['actPython'], '-I', '-m', 'agent_control_theory.canonical', '--inputs', str(inputs),
             '--output', str(root / 'act')], root / 'act.log', env)
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
    root.mkdir()
    write(root / 'launch-review.json', {'manifest': str(manifest), 'sha256': sha(manifest),
          'review': data['prelaunchReview'], 'runId': args.run_id, 'softSeconds': SOFT_SECONDS,
          'hardGraceSeconds': HARD_GRACE_SECONDS, 'maximumSeconds': 1800})
    env = os.environ.copy()
    env['V3_SOFT_DEADLINE_EPOCH_MS'] = str(int((time.time() + SOFT_SECONDS) * 1000))
    started = time.monotonic()
    command = ['timeout', '--signal=TERM', '--kill-after=' + str(HARD_GRACE_SECONDS) + 's', str(SOFT_SECONDS) + 's',
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
