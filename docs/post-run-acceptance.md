# Consolidated native acceptance

The project's bounded grant includes a small post-run evaluator that combines
existing evidence into one machine-readable receipt. It performs no provider call,
retry, build, archive repair or result rewrite. The live runner's frozen artifacts
remain unchanged, and each receipt records the evaluator and input hashes.

The evaluator checks manifest/task identities, actual Client outcome and observed
cleanup, source hashes/completeness, selected archive and detached reader, full
persisted Jury, independent build and coverage improvement, protected inputs,
native denied-action results, same-source ACT outputs and attempt/cost accounting.
It uses the existing full-Jury selection rules and original source records.

An overall ACCEPTED result requires every required gate. A failed full Jury is
TASK_FAILED; an observed contract violation is CONTRACT_FAILED; missing or
unqualified evidence is EVIDENCE_INCOMPLETE. Unknown cost and an unobserved denial
remain explicit. Semantic quality remains NOT_ASSESSED. A completed pipeline or
individual passing judge cannot replace the full Jury conclusion.

Controls must exercise a failed full Jury, absent evidence and changed evidence.
Checks that cannot be automated from retained outputs are named as manual and
cannot silently make the overall result green. The coordinator still reviews the
final evidence and controls any later counted attempt. This is a V3 domain receipt,
not another orchestration framework or Portfolio evidence ledger.

The implemented entry point is `scripts/accept_native_coverage.py --root <run>
--manifest <reviewed-manifest> --output <new-receipt>`. It reads retained evidence,
refuses to overwrite an existing receipt, and exits nonzero for every incomplete
or failed result. Ten separate gates retain input hashes and precise reasons.
Native denial checks use authored source tool results and the final denial
population, excluding embedded request copies. A reported terminal charge remains
unqualified until the separate source/assembly reader accepts the archive.

All fourteen Python checks pass, including full-Jury failure despite passing
individual judgments, missing evidence and changed required bytes. The evaluator
also rejects the retained full-pipeline fixture's actual failed Jury. Its first
native receipt reports EVIDENCE_INCOMPLETE with the original failed Workflow and
missing archive/Jury/ACT gates preserved. No Java or frozen launch artifact changes.

Each invocation freezes the observed cumulative ledger and both evaluator scripts
into a new receipt-owned input directory, records their original paths/hashes, and
executes the copied evaluator. Later reservations and source edits therefore leave
those historical references valid. A control changes a separate live ledger and
both source files after freezing, then verifies the retained inputs still match.

The third native attempt's frozen receipt passes all ten gates and was independently
accepted by the coordinator. Its exact hash and outcome are recorded in the
[final owning receipt](native-coverage-acceptance.md). Earlier incomplete receipts
remain unchanged; they are not replaced by the later successful attempt.
