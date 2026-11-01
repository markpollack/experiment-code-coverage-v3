# V3 consolidated baseline — 2026-11-01

**Main is the sole development line. Later work is paused.** This consolidation
preserves accepted source and observations; it authorizes no new experiment run.
The project itself holds its sanitized current-state record. There is no separate
V3 steward repository.

Implementation and normalization were pushed in
`d5ad4d848889aedc2c72ee7fbd3a2fb5551f09f2`; the subsequent receipt update adopts
the final Journal producer evidence without changing code, pins or tested bytes.

## Source disposition

| Input | Disposition |
|---|---|
| Prior remote main `b3d184d58a4dc4f38b15f7891b371c9f64b88b44` | Integrated ancestor |
| Original local main `5278fe2355e181f753cf73568787363787c1fcca` | Accepted license-history ancestor, preserved unchanged |
| `task/fresh-v3-integration` at `9ecbf9fc24b9becb96617e037ffc7aa2a5bfbb9d` | Accepted source integrated in full; branch retained as inactive history |
| Existing rescue refs at those original main/task tips | Frozen inactive archives; not advanced by consolidation |
| Historical data, plots, ignored environments and local planning | Retained in place; not development baselines |

The task input contains twelve commits after original local main, thirteen after
prior remote main. No divergent source commits, stashes or unexplained unique
branches were found. Both existing worktrees are retained: the main checkout has
15 untracked attention figures and historical artifacts; the task checkout has
ignored build artifacts. No worktree or branch was deleted. Main retains version
`0.1.0-SNAPSHOT`; no release, tag, deployment or license edit occurred here.

## Current declared dependency closure

All artifacts below are local builds with verified producer receipts, not newly
published coordinates. Build with an isolated Maven cache populated from those
receipts. No dependency-version command-line overrides were used.

| Dependency | Declared version | Source |
|---|---|---|
| Experiment runtime | `0.10.0-SNAPSHOT` | `01e7bc454c340e58504b035526c613f43a039b24` |
| Client model/core/Claude | `0.32.1-SNAPSHOT` | `66cc22569ea15b859b337f647b89382c9b9606fd` |
| Journal writer/TCK | `2.0.0-SNAPSHOT` | `a828d24eb1d6af642d373a05b2507ea72945b3b1` |
| SDK, via Client | `1.8.0-SNAPSHOT` | `810f5284cbb9d035a45ec0a77a31c0b08a2e192d` |
| Workflow / Judge | released `0.14.0` / `0.18.0` | Existing selected releases |
| Jackson BOMs | `2.22.4` / `3.2.4` | Existing declared defaults |

Journal's final source main above and owning receipt at steward main
`32f956494092cafbbe0bab163f82756f4b0a379b` are remotely verified and adopted.
The final artifact hashes equal the ones already tested by V3. Journal's owning
full-reactor, packaged acceptance and eleven semantic controls passed; V3 did not
rerun the upstream suite. Experiment's runtime passes its owning tests, but its
full legacy reactor is blocked on Judge/JEV compatibility. SDK, Client and
Experiment main pushes are held by their automatic publication workflows; their
existing task branches remain their owning source lines. Client also retains an
unwaived published-consumer policy gate. None of these is a whole-library readiness
claim or an authorization to publish.

The earlier accepted task-qualified artifacts, original 66-JAR run closures and
ACT `0.4.0.dev0+fresh.v3.2` wheel remain unchanged. Current source and normalized
artifacts do not retroactively describe the original runs.

## Verification and limits

- Complete V3 root offline `clean verify`: **11 tests, no failures/errors/skips**.
- Complete checked-in Python test discovery: **6 passed**.
- New runtime closure: **66 JARs including the application**; all **17** V3
  production class files equal the accepted task package byte-for-byte.
- Historical offline recheck: six April runs, 436 tool rows; simple 205 and
  hardened 146 admitted edges. Tables, counts and transition probabilities match
  the retained ACT rerun. This verifies descriptive analysis availability, not old
  absorption, cost or causal headline claims.
- No root GitHub workflows exist on prior main or the accepted input, so no main
  publication workflow is triggered by this source integration. Archive/rescue
  refs remain untouched.

The Java suite uses local fixtures; no task, quality judge provider, repeat or
model call was made. Dataset fixture builds are not an additional experiment.
Upstream full repositories were not retested by V3. Raw evidence, local artifact
manifests, logs, source bundles and exact rerun recipes remain private and local.

Historical V3 `5278fe2355e181f753cf73568787363787c1fcca` and ACT
`f1c502dbf79be82df28b37d7913bcc7f27383675` source/inputs are preserved before any
reader retirement. No reader is removed now; historical support is an archive,
not another maintained compatibility line. Current analysis targets canonical
Journal data. The original rerun environment is Python 3.12.3; the independent
consolidation recheck used Python 3.14.2 and produced equal counts. Exact package
inventories remain with each output.

Accepted observations remain **one simple plus one hardened** with differing
original runtimes. Four repeats remain unfinished and held. No homogeneous-runtime,
causal, statistical, efficiency or broader provider qualification follows.

No V3 consolidation blocker remains. Next action: have the respective owners
resolve upstream publication/full-reactor/policy gates before a separately
authorized next phase. See the [paused roadmap](ROADMAP.md).
