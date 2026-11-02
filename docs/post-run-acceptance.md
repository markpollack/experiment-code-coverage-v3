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
