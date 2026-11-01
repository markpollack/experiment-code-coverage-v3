# Coverage experiment v3

Runs the checked-in partial PetClinic task using the original simple and hardened-skills prompts, Claude Sonnet 4.6, and the shared Experiment canonical runtime. Java 21 is required.

The three concrete Workflow 0.14 stages prepare and measure the baseline, invoke Claude through Experiment's `ClaudeInvocationService`, and measure final coverage. The configured 30 minutes covers all three stages. Provider time is the remaining Workflow deadline minus five minutes for final measurement and 30 seconds for drain. The independent build judge has its existing ten-minute limit; the quality judge has three minutes.

T0 checks the real Maven build. T1 requires positive coverage improvement and at least 85% line coverage. T2 retains all six original rubric entries, equal weighting and the executable 0.5 threshold; 0.80 convergence is reported separately. Missing or malformed measurements and judge output are instrument errors. Workflow success alone does not establish task or capture success.

Build with the qualified task artifacts installed in an isolated Maven repository:

```sh
./mvnw -B -ntp -Dmaven.repo.local="$TASK_M2" verify dependency:build-classpath \
  -DincludeScope=runtime -Dmdep.outputFile=target/runtime-classpath.txt
```

Commit source before measurement. Use a new private directory outside this repository for every attempt, and freeze the packaged application and dependency JARs with their source receipts/hashes. A packaged invocation has this shape:

```sh
MAVEN_ARGS="-Dmaven.repo.local=$TASK_M2" java -Dcoverage.mavenRepo="$TASK_M2" \
  -cp "$FROZEN_CLASSPATH" io.tuvium.experiment.coverage.ExperimentApp \
  --project-root "$PROJECT_ROOT" --variant simple --replicate 0 \
  --run-id "$RUN_ID" --output-dir "$PRIVATE_NEW_ATTEMPT"
```

The application preserves launch inputs, global skill/configuration hashes, baseline/final XML, build logs, shared invocation receipts, Workflow snapshot and immutable Experiment results. CLI 2.1.316 is required by this selected acquisition path. No agent retry or interrupted-store resume happens automatically. Unsettled provider execution quarantines the workspace.

Qualify the immutable acquisition with Journal's supported independent packaged reader, then use its canonical export with the owning ACT adapter. An assembled canonical file or exported Parquet is not source verification. Only after the complete one-variant vertical pilot passes should the second variant and remaining replicates run. The target is three valid measurements per variant; observed domain failure can be valid data, while capture/instrument failures remain separate attempts.

Legacy analysis scripts are retained for historical datasets. Fresh results use the versioned Experiment canonical envelope and the ACT canonical adapter; they must not be passed to the legacy PhaseCapture loader.
