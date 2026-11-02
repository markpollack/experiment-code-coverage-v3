# Bounded async coverage validation

**Completed:** the [final native coverage receipt](native-coverage-acceptance.md)
records acceptance of the third bounded attempt. The dispositions and design
checkpoints below preserve the preparation and repair sequence.

## Current disposition

A bounded implementation scope is accepted for a separate deterministic validation
path through the installed async Client, Journal and Experiment artifacts. The
frozen consolidation baseline and historical observations remain unchanged. This
path is not yet launchable: the qualified native async invocation and associated
complete archive are upstream dependencies.

The checked-in task is spring-petclinic-partial with the simple prompt. Its exact
before-tree is `6d9d5311952f7a022e5d30af8693d11d932186f9`; item SHA256 is
`6b037a95607b76792d57c22ae20d4ea4ba9cc57e0895dc34e44520d7ff185b2a` and prompt
SHA256 is `8084017e1d431000240d93568a975d9141f57b2d12553d3d8dad1086b6cffb15`.
An external PetClinic checkout or a changed prompt is a different task.

## Design

V3 owns task setup, domain measurements and mapping. Experiment owns invocation
orchestration and immutable result/Jury persistence; Client owns the native async
lifecycle; Journal owns source qualification and independent archive acceptance.
V3 will not implement another provider engine or infer native capability from a
deterministic provider fixture.

The separate deterministic Jury performs a fresh Maven build/test and JaCoCo
measurement, requires at least 85% line coverage plus positive improvement, and
validates the selected edit surface. It preserves actual verdicts in the full
Jury document. Semantic quality is explicitly NOT_ASSESSED; this is a different
validation purpose from the existing semantic T2 path, not an equivalent T2 pass.
No model judge is invoked in this validation mode.

Launch readiness must establish all of the following before external work:

- exact installed artifact identities and a qualified current native async route;
- the pinned task bytes, a fresh workspace and one fresh run identity;
- enforced subject wall time 900 seconds, cleanup 15 seconds, overall 1800 seconds;
- enforced native spending cap USD 5 and zero judge-inference calls;
- an allowlisted test-source/resource and selected test-build edit policy applied
  before tools execute, with post-run integrity checks as a separate control;
- actual accepted Client decision/association, native complete archive and a
  separate installed independent reader, full Jury persistence and ACT outputs.

The live-run ceiling is six authorized coverage attempts shared with programme
coordination. Preparation and offline tests consume no native run. Native launch
requires a named dependency checkpoint with exact artifacts, enforcement proof
and acceptance plan; unavailable upstream capabilities keep it NOT_LAUNCHABLE.
No legacy blocking fallback, provider substitution or implicit provider retry is
permitted. Failed persistence must not replay a completed subject edit.

## Custody and verification

Main remains the sole development line. Preserve the historical plots, ignored
datasets/environments, accepted runtime closures and frozen source bundles.
Build/test source exports and private evidence use new directories outside the
checkout, so retained target outputs and historical raw bytes remain unchanged.
Run complete offline Java verification and the checked-in Python suite for
implementation changes. No provider, semantic judge, publication or deployment
is implied by a passing local build.

## Implemented preparation checkpoint

`PinnedCoverageTask.prepare` verifies the exact before-tree, item/prompt hashes and
all selected Git-normalized blob hashes before copying the original checkout bytes
for tracked inputs into a new workspace. Declared CRLF/LF checkout attributes are
honored; normalization does not alter the copied input bytes.
Ignored historical build artifacts are excluded. `CoverageEditPolicy.beforeTool`
refuses unselected tools, symbolic/escaping paths, production/POM edits and arbitrary
or background shell commands. The selected edit surface is src/test only: this
pinned fixture already has its JaCoCo/build configuration. The one allowed Bash
command is the exact foreground `./mvnw clean test jacoco:report`.

`DeterministicCoverageJury` first checks protected-file integrity, then performs an
independent bounded build and coverage judgment with no semantic judge. Its
metadata says semantic quality NOT_ASSESSED. A policy object and post-run diff are
not proof that the native provider installed/enforced the policy; that upstream
wiring and real before-tool control are still launch prerequisites. This tool
policy is not an OS filesystem sandbox: source integrity is checked separately.
The original semantic runner is unchanged and must not be used as an async fallback.

## Verified preparation result

The standalone preparation increment passes the complete offline Java suite
(21 tests) and all six checked-in Python mapping tests. The packaged input copier
selected 116 pinned source files and excluded ignored build outputs; deliberately
changed source and prompt bytes refused before creating a workspace. An initial
CRLF-versus-Git-LF mismatch in the Windows wrapper was corrected by honoring its
declared checkout attributes while retaining the original copied bytes.

The coordinator's Glob review was reproduced before correction: an absolute
pattern bypassed the omitted path check. The policy now requires relative ordinary
`*`/`?` patterns with no parent components, brace/bracket/escape expansion, or
symbolic search tree. Useful `**/*.java` and `src/test/**/*Test.java` remain valid.
The failing regression and corrected full checks are retained in private custody.

A separate installed-JAR producer persisted the real deterministic Jury through
Experiment's full V6 recording/store path. Another JVM without V3, Client or
Journal writer classes reopened the exact producer document and retained semantic
quality NOT_ASSESSED. It was an explicit fixture with no provider workflow, not a
PetClinic measurement or native/archive qualification.

Current prepared application JAR SHA256:
`bcd9041b3673b5d265a381bf136245cd127b74973ac3a64232b2737e1abdc2a2`.
The exact installed dependency manifest is retained privately; identical snapshot
coordinates alone are insufficient to select this artifact. Historical 66-JAR
closure and all 19,789 preexisting local extra entries remain hash-identical.

Native coverage attempts remain 0 of 6; inference spend remains USD 0. The async
invocation adapter, actual pre-tool hook installation, native spend/deadline
propagation, complete archive/independent reader and ACT output are still required
before a live prelaunch checkpoint. No legacy runner fallback is authorized.

## Native runner adoption — active bounded scope

The next bounded implementation step accepts the installed public native Client
provider and Experiment's configured async start/association mapper. The runner
will use one fresh one-shot connection, the selected required tool policy,
claude-sonnet-4-6, actual execution timeout and a USD 5 native generation cap.
Client owns admission, decisions, deadline/cancel/cleanup and recording; Experiment
owns native Invocation conversion and immutable storage. V3 will not add another
provider engine or substitute the historical blocking runner.

The runner must retain original capture bytes, native source qualification, the
receiver binding selected by its actual completion receipt, independent TCK-only
archive readback, full deterministic Jury and ACT output. Source capture overflow
at 4 MiB is incomplete evidence and cannot pass. Shutdown/escaped-child containment
is not added as a prerequisite to the explicitly observed-resource cleanup scope.
The exact repaired SDK/Journal artifacts and Experiment native mapping are required
before native execution. Actual hook/argv/model/cap wiring will first be checked
with deterministic subprocesses against the installed APIs. A new prelaunch review
must accept exact source/artifact/input pins and enforcement evidence before any
of the six coverage attempts is submitted. Counter remains 0/6 and spend USD 0.

### Installed Client wiring checkpoint

The concrete configuration uses the public native provider with model pinned in
AgentClient default options, the required synchronous before-tool callback, USD 5
native cap, 900-second subject limit and 15-second observed-resource cleanup.
Deterministic subprocess checks confirm actual model/budget argv, allowed test
writes and denied protected/absolute-glob actions, deadline cessation, unsuccessful
budget exhaustion and incomplete capture on 4 MiB overflow. These are wiring
checks, separate from native/archive qualification. Failed policy evidence denies
the tool. Hooks also require the actual selected working directory.

The packaged entry point requires matching `coverage.mavenRepo` and inherited
`MAVEN_ARGS=-Dmaven.repo.local=<task-local repository>`; this routes the exact allowed
Maven command to the selected copied cache without changing pinned task bytes or
shared configuration. Native response accounting remains UNKNOWN until supported
by qualified scoped terminal/source/ACT evidence. Each submission reserves the
USD 5 enforced cap; an unknown actual cost is never reported as zero.

The native domain path reuses baseline/final measurement logic while keeping the
native Invocation separate from the historical blocking service. ACT mapping
retains the actual Client source run UID, Experiment invocation ID and Workflow
run/attempt IDs as distinct identities. Its deterministic verdict includes edit
integrity/build/coverage and semantic NOT_ASSESSED. The separate reader source
reopens immutable Experiment/Jury storage and runs full Journal acquisition and
assembly verification before exporting canonical inputs; its classpath excludes
the V3 application, Client, SDK and Journal writer.

A coordinator-selected private ledger reserves each native submission before
admission and refuses duplicate run identities or a seventh reservation. An
uncertain admission remains counted. The launch wrapper applies a 1785-second
soft deadline plus at most 15 seconds of termination grace, keeping the hard
campaign bound at 1800 seconds across application, readback and ACT. It creates
only the fresh campaign process group and does not target unrelated processes.

The boundary review identified a failure-custody gap when resource settlement or
recording observation throws before the attempt snapshot. The accepted correction
retains actual available handle/decision/recording observations in a bounded final
snapshot on every admitted exceptional path, with unavailable settlement explicitly
unknown. It does not elect a replacement outcome or replay the subject. Preparation
integrity and expanded prompt hashes are retained before native submission.
Processing completion is distinct from a passing coverage/Jury result.

The activated continuation preserves the existing run counters and owning scope.
The proposed USD 5 cap is a technical limit; live submission also requires recorded
Owner dollar approval in the concrete coordinator-reviewed prelaunch manifest.
A passing deterministic fixture does not consume or authorize native inference.

## Observed response custody amendment

The second native attempt exposed a postprocessing boundary: the live Client
response was local when independent archive validation threw. Its original native
success/source/decision survive, but no observed Client response projection or
accepted Invocation was persisted. The failed Workflow remains immutable.

The accepted narrow correction captures exact observed result text (including
null), success flag, observation state/time and original Attempt/Client identities
immediately when the actual result returns. Retrieval errors retain THREW with no
invented text. Keep the observation in outer scope; settle resources before a
custody write may escape, retain it before archive admission and again through
the existing finally path on failure. Identical repeated retention is allowed;
conflicting prior bytes are refused without overwrite. Original failures remain
primary and custody failures remain visible.

This is diagnostic projection custody, not an AgentClientResponse serialization,
new admission API, replacement handle or recovery engine. A real Client fixture
followed by forced verifier failure must retain the actual response/identity and
observed cleanup while preserving failure and absence of invocation output.
Journal's owning normalization repair and a new named review are still required
before another counted native attempt.

The implemented custody projection records RETURNED/THREW/NOT_OBSERVED explicitly
and uses create-new or identical-byte retention. A conflicting existing file is
left intact, with the custody error recorded beside the original attempt failure.
Four targeted actual-Client/Workflow checks pass: successful response followed by
forced archive rejection, result retrieval failure, conflicting custody after
confirmed cleanup, and an unsettled observation with honest missing termination.
The latter fixture retains a 25 ms observation timeout with a separate two-second
cleanup allowance so evidence writing does not race its artificial 100 ms limit.
Production cleanup remains 15 seconds. Final full gates use the next exact repaired
dependency composition before any third-run review.
