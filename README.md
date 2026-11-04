# Coverage experiment v3

**Current state: main is the sole development line. Bounded async validation preparation is active; native execution remains gated on exact upstream qualification and enforced launch controls.**
Read the [current disposition](docs/house-in-order.md), [frozen tested baseline](docs/current-baseline.md) [roadmap](docs/ROADMAP.md) and [async validation design](docs/async-validation.md) first.

The application runs the checked-in partial PetClinic task using the original simple and hardened-skills prompts, Claude Sonnet 4.6, and the shared Experiment canonical runtime. Java 21 is required.

The three concrete Workflow 0.14 stages prepare and measure the baseline, invoke Claude through Experiment's `ClaudeInvocationService`, and measure final coverage. The configured 30 minutes covers all three stages. Provider time is the remaining Workflow deadline minus five minutes for final measurement and 30 seconds for drain. The independent build judge has its existing ten-minute limit; the quality judge has three minutes.

T0 checks the real Maven build. T1 requires positive coverage improvement and at least 85% line coverage. T2 retains all six original rubric entries, equal weighting and the executable 0.5 threshold; 0.80 convergence is reported separately. Missing or malformed measurements and judge output are instrument errors. Workflow success alone does not establish task or capture success.

Verify offline with the current declared development artifacts installed from exact producer receipts in an isolated Maven repository (`V3_M2`). These artifacts are local, not published:

```sh
./mvnw -o -B -ntp -Dmaven.repo.local="$V3_M2" clean verify dependency:build-classpath \
  -DincludeScope=runtime -Dmdep.outputFile=target/runtime-classpath.txt
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/tests -v
```

The invocation below is reference documentation, not permission to run. New measurements and quality calls require separate explicit authorization. Commit source before any authorized measurement. Use a new private directory outside this repository for every attempt, and freeze the packaged application and dependency JARs with their source receipts/hashes. A packaged invocation has this shape:

```sh
MAVEN_ARGS="-Dmaven.repo.local=$V3_M2" java -Dcoverage.mavenRepo="$V3_M2" \
  -cp "$FROZEN_CLASSPATH" io.tuvium.experiment.coverage.ExperimentApp \
  --project-root "$PROJECT_ROOT" --variant simple --replicate 0 \
  --run-id "$RUN_ID" --output-dir "$PRIVATE_NEW_ATTEMPT"
```

The application preserves launch inputs, global skill/configuration hashes, baseline/final XML, build logs, shared invocation receipts, Workflow snapshot and immutable Experiment results. CLI 2.1.316 is required by this selected acquisition path. No agent retry or interrupted-store resume happens automatically. Unsettled provider execution quarantines the workspace.

Qualify the immutable acquisition with Journal's supported independent packaged reader, then use its canonical export with the owning ACT adapter. An assembled canonical file or exported Parquet is not source verification. Two bounded observations (one per variant) are accepted. Four remaining replicates are held until separately authorized; earlier pilot sequencing instructions are historical. The target is three valid measurements per variant; observed domain failure can be valid data, while capture/instrument failures remain separate attempts.

Legacy analysis scripts are retained for historical datasets. Fresh results use the versioned Experiment canonical envelope and the ACT canonical adapter; they must not be passed to the legacy PhaseCapture loader.

## Live results and fixture custody

The default `scripts/analyze_fresh.py` command requires `--launch-manifest` in
addition to its original result/export/reader/evidence arguments. It binds the
actual launch record to the immutable Experiment result before admitting rows to
ACT. `SYNTHETIC_FIXTURE` and `FIXTURE` produce an empty live input population:
zero trials, successes and spend contribution, with no live coverage or J.
Missing or ambiguous launch provenance refuses. Generic Experiment results and
diagnostic ACT tables alone are not live qualification.

The production pipeline supplies the exact manifest and stops before ACT for an
excluded population. Native candidate acceptance recomputes that same admission.
Historical diagnostic projections and nine-table fixture outputs remain frozen.
The canned child/template/test corpus now live only in test resources and are
absent from the production application JAR. The native guard checks both selected
file names and JAR members, source drift and exact artifacts before authorization.
This removes supplied fixture inputs; the subject's ordinary filesystem read
access still permits reading other user-readable host files.
