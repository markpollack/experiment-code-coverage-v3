# V3 housekeeping disposition — 2026-11-01

Bounded instruction/readiness cleanup and inactive-checkout retirement are
complete. Main remains the sole development line. The project owns this
disposition; no separate V3 steward is appointed. The implementation and POM
are unchanged from the [frozen tested baseline](current-baseline.md).

## Current upstream readiness

These are consumed owning handoffs, separate from V3's tested dependency closure.
Matching snapshot coordinates alone do not establish matching artifact bytes.

| Producer | Current source witness | Consumer disposition |
|---|---|---|
| Experiment | `987951febf231f3c4f165db050cfc12d159908d6` | Current supported migration has advanced beyond the historical legacy-reactor blocker. Await its final packaged artifact handoff before any separately scoped V3 adoption. |
| Client | `66cc22569ea15b859b337f647b89382c9b9606fd` | Unchanged one-shot implementation. Accepted policy and proposed session design remain separate from Owner review/design gates; no new async API is adopted. |
| Journal | `9009f14bfaf4c6356943af6e3a69258f7d55a751` | Existing writer/reader late-finalization proof is test-only; installed writer/TCK production bytes are unchanged. This does not qualify retained sessions or a new schema. |
| SDK | `810f5284cbb9d035a45ec0a77a31c0b08a2e192d` | Same pinned development candidate; release HOLD and consumer/qualification limits remain. |
| ACT | `1110a4316247f75d69ce440b5ce6879022c2c761` | Canonical calculation/extraction checks are accepted owner evidence. Preserve the exact historical wheel and recipe; no statistical inference or new V3 rerun follows. |

No POM or installed artifact is changed here. V3's prior complete offline Java
verification (11 tests) and checked-in Python suite (6 tests) remain the evidence
for the unchanged implementation. All 66 frozen runtime JAR hashes were rechecked
and match. No upstream build, historical analysis or provider execution is rerun
solely for this documentation/cleanup change.

## Checkout and evidence custody

The repository moved from two registered/present checkouts to one normal main
checkout on Feynman. The inactive accepted task checkout was removed normally
after its 39 ignored build/cache files were archived outside the checkout,
extracted, read and hash-compared. Its exact source commit remains on live origin;
no branch, tag or rescue ref was moved or deleted. No competing writer was found.

All 19,786 original ignored/untracked files in main, including the 15 untracked
attention plots, remain byte-identical. Historical captures and datasets stay in
private custody. Frozen V3 `5278fe2355e181f753cf73568787363787c1fcca` and ACT
`f1c502dbf79be82df28b37d7913bcc7f27383675` source bundles verify. The original
Python 3.12.3 environment, pinned classifier/inputs, exact recipe and prior
analysis remain readable; historical analysis is a rerunnable archive.
The earlier private source export, isolated Maven cache, frozen run closures and
restoration copies are evidence artifacts outside retired development checkouts.
Their owning private record supplies hashes and byte accounting.

No project publication workflow or mandatory local hook was found. The global
rescue auto-push hook is suppressed only for the scoped record commit so frozen
rescue refs remain unchanged; no global configuration or validation gate changes.

## Stop and resume

Relevant new steering is checked at safe step boundaries and before completion.
Only actual recipient acknowledgments establish acceptance; unrelated historical
backlog remains deferred. The bounded grant ends at the owning receipt.

Implementation and dependency adoption require a separately named closure and
task. Owner-led Client review/design gates and upstream publication holds remain
with their owners. Codex qualification is the next bounded provider stage for
planning only. Historical reader retirement, four Claude repeats, broader
studies, new inference, releases, tags and deployment remain deferred.
