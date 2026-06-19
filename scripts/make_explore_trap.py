#!/usr/bin/env python3
"""The EXPLORE-trap figure: the honest control-measure visual for the ACT post.

Shows the *mechanism* behind the -37%: the EXPLORE self-loop. Reducing how often
the run falls back into EXPLORE (self-loop P, dwell, expected returns) is what lowers
the Foster-Lyapunov value V(EXPLORE) = expected steps to done.

Reuses the project's own classifier + markov-agent-analysis absorbing-chain math, so
every number is data-true and regenerable. Writes PNG+SVG to docs/figures/attention/.
"""
from __future__ import annotations
import sys
from pathlib import Path
import numpy as np
import duckdb
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import Circle, Arc, FancyArrowPatch, FancyBboxPatch

HERE = Path(__file__).resolve().parent
PROJ = HERE.parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(Path.home() / "projects" / "markov-agent-analysis" / "src"))
from make_markov_analysis import classify_state, STATES  # noqa: E402
from markov_agent_analysis.fundamental import (  # noqa: E402
    build_absorbing_chain_from_traces, compute_fundamental_matrix,
)

GRAY, TEAL, INK, MUTE = "#8a8a8a", "#1a7a7a", "#222222", "#777777"

def chain_numbers(classified, items, variant):
    Q, R, ts = build_absorbing_chain_from_traces(classified, items, STATES, variant)
    N = compute_fundamental_matrix(Q)
    h = N.sum(axis=1)
    i = list(ts).index("EXPLORE")
    p = float(Q[i, i])
    return dict(p_stay=p, dwell=1.0/(1.0-p), returns=float(N[0, i]), V=float(h[i]))

def load():
    con = duckdb.connect()
    tools = con.execute(f"SELECT * FROM '{PROJ}/data/curated/tool_uses.parquet'").df()
    items = con.execute(f"SELECT * FROM '{PROJ}/data/curated/item_results.parquet'").df()
    con.close()
    tools = tools.sort_values(["variant", "item_id", "global_seq"]).copy()
    tools["semantic_state"] = [classify_state(tn, tt) for tn, tt in zip(tools.tool_name, tools.tool_target)]
    return tools[tools.semantic_state.notna()].copy(), items

def self_loop(ax, cx, cy, r, p, color):
    """A self-loop arrow above an EXPLORE node; line weight encodes P(stay)."""
    lw = 2.0 + p * 8.0
    loop_r = 0.30 + 0.38 * p                # stickier => bigger loop
    cyl = cy + r + loop_r * 0.55
    ax.add_patch(Arc((cx, cyl), 2*loop_r, 2*loop_r, theta1=-58, theta2=212, lw=lw, color=color, zorder=3))
    th = np.deg2rad(-58)
    hx, hy = cx + loop_r*np.cos(th), cyl + loop_r*np.sin(th)
    ax.add_patch(FancyArrowPatch((hx+0.12, hy+0.17), (hx, hy), arrowstyle="-|>",
                                 mutation_scale=11+8*p, color=color, lw=lw, zorder=4))

def panel(ax, cx, d, color, label):
    cy, r = 4.35, 0.72
    ax.text(cx, 6.55, label, ha="center", va="center", fontsize=14, fontweight="bold", color=color)
    self_loop(ax, cx, cy, r, d["p_stay"], color)
    ax.add_patch(Circle((cx, cy), r, facecolor=color, edgecolor=color, alpha=0.15, lw=2, zorder=2))
    ax.add_patch(Circle((cx, cy), r, facecolor="none", edgecolor=color, lw=2.2, zorder=3))
    ax.text(cx, cy, "EXPLORE", ha="center", va="center", fontsize=12, fontweight="bold", color=color, zorder=5)
    ax.text(cx, 3.34, f"stays {d['p_stay']:.0%} of the time", ha="center", va="top", fontsize=11.5, fontweight="bold", color=color)
    ax.text(cx, 2.98, f"≈ {d['dwell']:.0f} steps per visit", ha="center", va="top", fontsize=11, color=INK)
    ax.text(cx, 2.62, f"≈ {d['returns']:.0f} returns to EXPLORE", ha="center", va="top", fontsize=11, color=INK)
    ax.add_patch(FancyArrowPatch((cx, 2.28), (cx, 1.58), arrowstyle="-|>", mutation_scale=15, color=MUTE, lw=1.6))
    ax.add_patch(FancyBboxPatch((cx-1.05, 0.5), 2.10, 0.98, boxstyle="round,pad=0.02,rounding_size=0.12",
                                facecolor=color, edgecolor="none", alpha=0.12, zorder=1))
    ax.text(cx, 1.14, f"{d['V']:.0f}", ha="center", va="center", fontsize=26, fontweight="bold", color=color)
    ax.text(cx, 0.69, "expected steps to done", ha="center", va="center", fontsize=10, color=MUTE)

def main():
    classified, items = load()
    s = chain_numbers(classified, items, "simple")
    h = chain_numbers(classified, items, "hardened-skills")
    pct = (1 - h["V"]/s["V"]) * 100
    print(f"simple  : {s}")
    print(f"hardened: {h}")
    print(f"V drop  : {s['V']:.1f} -> {h['V']:.1f}  ({-pct:.0f}%)")

    fig, ax = plt.subplots(figsize=(11, 7.2))
    ax.set_xlim(0, 11); ax.set_ylim(0, 8); ax.axis("off")
    ax.text(5.5, 7.80, "The EXPLORE trap", ha="center", va="top", fontsize=20, fontweight="bold", color=INK)
    ax.text(5.5, 7.30, "Expected steps to done — the Foster–Lyapunov value of the run's start state, from a Markov model.\n"
                       "Loosening the self-loop is what lowers it. Not a raw tool-call count.",
            ha="center", va="top", fontsize=11, style="italic", color=MUTE)
    panel(ax, 3.0, s, GRAY, "simple")
    panel(ax, 8.0, h, TEAL, "hardened+skills")
    ax.add_patch(FancyArrowPatch((4.15, 1.05), (6.85, 1.05), arrowstyle="-|>",
                                 mutation_scale=16, color=TEAL, lw=2.2, connectionstyle="arc3,rad=-0.20"))
    ax.text(5.5, 1.92, f"−{pct:.0f}%", ha="center", va="center", fontsize=22, fontweight="bold", color=TEAL)
    ax.text(5.5, 1.48, "expected work remaining", ha="center", va="center", fontsize=10, color=MUTE)

    out = PROJ / "docs" / "figures" / "attention"
    out.mkdir(parents=True, exist_ok=True)
    for ext in ("png", "svg"):
        fig.savefig(out / f"explore_trap.{ext}", dpi=150, bbox_inches="tight")
    print(f"[written] {out}/explore_trap.png (+svg)")

if __name__ == "__main__":
    main()
