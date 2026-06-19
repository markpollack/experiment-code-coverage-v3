#!/usr/bin/env python3
"""Extract concrete Lyapunov-function inputs from the v3 Markov analysis.

Reuses the project's own classifier (classify_state) + the markov-agent-analysis
absorbing-chain math to compute, per variant:
  - N = (I-Q)^-1 fundamental matrix
  - V(s) = h(s) = row sums of N  = expected steps to absorption from state s  (the Lyapunov fn)
  - expected visits from start  = N[0, :]
  - b_success(s) = (N @ R)[s, success]
  - self-loop P[s,s] and dwell 1/(1-P[s,s])
  - cost-to-go V_$(s) = c_bar * h(s), with c_bar = mean cost per tool call (no per-state cost in data)

Writes a markdown report to ~/scripts/inbox/markov-lyapunov-values.md and prints it.
"""
from __future__ import annotations
import sys
from pathlib import Path
import numpy as np
import duckdb

HERE = Path(__file__).resolve().parent
PROJ = HERE.parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(Path.home() / "projects" / "markov-agent-analysis" / "src"))

from make_markov_analysis import classify_state, STATES  # noqa: E402
from markov_agent_analysis.fundamental import (  # noqa: E402
    build_absorbing_chain_from_traces,
    compute_fundamental_matrix,
    compute_p_success,
)

ABS = ["SUCCESS", "FAILURE"]  # R columns: 0=success, 1=failure

con = duckdb.connect()
tools = con.execute(f"SELECT * FROM '{PROJ}/data/curated/tool_uses.parquet'").df()
items = con.execute(f"SELECT * FROM '{PROJ}/data/curated/item_results.parquet'").df()
con.close()

tools = tools.sort_values(["variant", "item_id", "global_seq"]).copy()
tools["semantic_state"] = [classify_state(tn, tt) for tn, tt in zip(tools.tool_name, tools.tool_target)]
classified = tools[tools["semantic_state"].notna()].copy()

variants = [v for v in ["simple", "hardened-skills"] if v in set(classified.variant)]

lines = []
def out(s=""):
    lines.append(s)

out("# Markov Lyapunov values — concrete numbers")
out("")
out("Computed via the project's own `classify_state` + `markov-agent-analysis` absorbing-chain math.")
out("`V(s)` = expected steps to absorption from state `s` (row sums of N) — the Foster–Lyapunov function (drift −1).")
out("")

for v in variants:
    Q, R, ts = build_absorbing_chain_from_traces(classified, items, STATES, v)
    N = compute_fundamental_matrix(Q)
    h = N.sum(axis=1)                       # V(s): expected steps to absorption from each state
    ev0 = N[0, :]                           # expected visits from start state (EXPLORE)
    bsucc = compute_p_success(N, R, STATES, ABS, "SUCCESS")
    selfloop = np.diag(Q)
    dwell = np.where(selfloop < 1, 1.0 / (1.0 - selfloop), np.inf)

    vitems = items[items.variant == v]
    ncalls = int((classified.variant == v).sum())
    total_cost = float(vitems.cost_usd.sum())
    total_tokens = int(vitems.total_tokens.sum())
    c_per_call = total_cost / ncalls
    tok_per_call = total_tokens / ncalls
    V0 = h[0]                               # expected steps from start = sum(N[0,:])

    out(f"## {v}")
    out("")
    out(f"- tool calls (classified): **{ncalls}**, runs/items: {len(vitems)}, pass rate: {vitems.passed.mean():.0%}")
    out(f"- mean cost/call: **${c_per_call:.4f}**  ·  mean tokens/call: **{tok_per_call:,.0f}**")
    out(f"- expected steps from start (EXPLORE) = **V(EXPLORE) = {V0:.1f}**  ·  expected total cost ≈ **${c_per_call*V0:.2f}**")
    out("")
    out("| state | V(s)=exp.steps | exp.visits from start | b_success | self-loop P[s,s] | dwell | V_$(s)=c̄·V(s) |")
    out("|-------|---------------:|----------------------:|----------:|-----------------:|------:|---------------:|")
    for i, s in enumerate(STATES):
        dw = f"{dwell[i]:.2f}" if np.isfinite(dwell[i]) else "—"
        out(f"| {s} | {h[i]:.1f} | {ev0[i]:.2f} | {bsucc[s]:.3f} | {selfloop[i]:.3f} | {dw} | ${c_per_call*h[i]:.3f} |")
    out("")

out("Notes:")
out("- `b_success ≡ 1.0` on visited states because **every run in the dataset passed** (no FAILURE absorption observed); the")
out("  success-probability Lyapunov function is degenerate here — use V(s)=expected steps (or cost-to-go) instead.")
out("- States with `exp.visits from start = 0` (e.g. READ_KB, JAR_INSPECT) are never entered in these traces, so their V(s) is")
out("  the trivial self-value (1) and not meaningful.")
out("- `V_$(s) = c̄ · V(s)` uses a single mean cost/call `c̄` (cost is recorded per run, single `agent-run` phase — there is no")
out("  per-state cost signal in the data). True per-state cost weights would need per-tool-call cost instrumentation.")

report = "\n".join(lines)
print(report)
dest = Path.home() / "scripts" / "inbox" / "markov-lyapunov-values.md"
dest.write_text(report + "\n")
print(f"\n[written] {dest}")
