# Bounded async coverage validation

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
