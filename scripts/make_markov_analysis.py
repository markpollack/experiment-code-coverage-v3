#!/usr/bin/env python3
"""
Markov chain analysis — template wrapper.

Starting point: 9-state taxonomy from the tuvium-code-coverage-v2 experiment
(Java/Maven/Spring test-writing agents). This is a proven classifier for
code-generation and test-writing workflows. Adapt for your domain.

CUSTOMIZE for a new domain:
  1. Update STATES — add/remove/rename states to match your workflow
  2. Update classify_state() — map your tool calls to those states
     - Run MARKOV_DISCOVERY=true first to see raw tool:target frequencies
     - Keep the Bash subclassification pattern; just change the keywords
  3. Update CLUSTER_DEFINITIONS — define which state groups represent
     friction (e.g., FIX_LOOP), forward progress (e.g., PRODUCTIVE), etc.
  4. Update VARIANT_ORDER, DELTA_PAIRS, NOTE_MAP to match your variants
  5. Update COLORS (optional — defaults are fine for exploration)

Bootstrap procedure (do this BEFORE finalizing the taxonomy):
  1. Run one control variant (N=1) to generate tool_uses.parquet
  2. Run with MARKOV_DISCOVERY=true to see raw tool name + target frequencies:
         MARKOV_DISCOVERY=true python scripts/make_markov_analysis.py
     Then inspect: SELECT state, count(*) FROM tool_uses GROUP BY 1 ORDER BY 2 DESC
  3. Cluster the top-N patterns into named states
  4. Write classify_state() based on real data, then re-run normally

State taxonomy rationale (code-coverage-v2):
  EXPLORE     — targeted file access via Read/Glob (agent knows where to look)
  SHELL       — unstructured shell searching via Bash find/ls/grep (agent is casting a net)
  READ_KB     — reading knowledge base files (knowledge/ dir)
  READ_SKILL  — invoking a modular knowledge skill (Skill tool)
  JAR_INSPECT — spelunking Maven cache for class/import discovery (jar tf, javap)
  WRITE       — writing output files for the first time (forward progress)
  BUILD       — running the build/test pipeline (./mvnw, ./gradlew)
  VERIFY      — reading back results to confirm success (JaCoCo, test report)
  FIX         — editing output files after a failure (rework)

  SEARCH cluster = SHELL + JAR_INSPECT (all unstructured searching)
  FIX_LOOP cluster = FIX + BUILD (rework cycle)

Requires markov-agent-analysis library:
    uv pip install -e ~/tuvium/projects/markov-agent-analysis/[all]

Run:
    python scripts/make_markov_analysis.py
    MARKOV_DISCOVERY=true python scripts/make_markov_analysis.py   # discovery mode
"""

import os

from pathlib import Path
import duckdb
import matplotlib
matplotlib.use("Agg")

from markov_agent_analysis import MarkovAnalysisPipeline
from markov_agent_analysis.transitions import apply_classify, build_transition_counts, normalize_to_probability_matrix
from markov_agent_analysis.figures import make_single_transition_matrix

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------

PROJECT_ROOT = Path(__file__).resolve().parent.parent
DATA_DIR = PROJECT_ROOT / "data" / "curated"
OUTPUT_DIR = PROJECT_ROOT / "docs" / "figures"
ANALYSIS_DIR = PROJECT_ROOT / "analysis"

# ---------------------------------------------------------------------------
# Discovery mode — set MARKOV_DISCOVERY=true to emit raw tool:target labels
# instead of semantic states. Use this on first run to calibrate classify_state().
# ---------------------------------------------------------------------------

DISCOVERY_MODE = os.environ.get("MARKOV_DISCOVERY", "false").lower() == "true"
if DISCOVERY_MODE:
    print("DISCOVERY MODE: classify_state() returning raw tool:target labels")
    print("Run: SELECT state, count(*) FROM tool_uses GROUP BY 1 ORDER BY 2 DESC LIMIT 30")
    print()

# ---------------------------------------------------------------------------
# CUSTOMIZE: State taxonomy for your domain
# ---------------------------------------------------------------------------

# 9-state taxonomy from code-coverage-v2 (Java/Maven test-writing).
# For a different domain, replace these with your workflow's semantic phases.
# Start with 5-6 states; add more only after inspecting discovery-mode output.
STATES = [
    "EXPLORE",      # structured file access: Read tool, Glob, Agent subagent
    "SHELL",        # shell-based exploration: find, ls, grep, cat, tree — casting a net
    "READ_KB",      # reading knowledge base files (knowledge/ dir)
    "READ_SKILL",   # invoking a SkillsJars skill (Skill tool call)
    "JAR_INSPECT",  # jar tf/xf, javap — agent spelunking .m2 to find imports
    "WRITE",        # writing output files (first time — forward progress)
    "BUILD",        # ./mvnw clean test jacoco:report — actual execution
    "VERIFY",       # reading back results to confirm coverage/success
    "FIX",          # editing output files after a failure — rework
]

# CUSTOMIZE: per-state colors for charts
# These colors were chosen for code-coverage-v2 to group related states visually:
# blues = exploration family, greens = knowledge family, purples/yellows = execution
COLORS = {
    "EXPLORE":     "#4C72B0",
    "SHELL":       "#9ecae1",   # lighter blue — related to EXPLORE but unstructured
    "READ_KB":     "#55A868",
    "READ_SKILL":  "#29a8ab",
    "JAR_INSPECT": "#937860",
    "WRITE":       "#2ca02c",
    "BUILD":       "#8172B2",
    "VERIFY":      "#CCB974",
    "FIX":         "#C44E52",
}

# CUSTOMIZE: display order for variants in all charts
VARIANT_ORDER = [
    "simple",
    "hardened-skills",
]

# CUSTOMIZE: cluster definitions
# FIX_LOOP: rework cluster — agent retrying after failure
# PRODUCTIVE: forward-progress cluster — writing for the first time
# SEARCH: all unstructured searching (efficiency tax) — key metric for knowledge interventions
# JAR_INSPECT: framework friction specifically addressable by KB injection
CLUSTER_DEFINITIONS = {
    "FIX_LOOP":   ["FIX", "BUILD"],          # rework cycle: edit → rebuild
    "PRODUCTIVE": ["WRITE"],                  # forward progress: writing output
    "SEARCH":     ["SHELL", "JAR_INSPECT"],  # all unstructured searching
    "JAR_INSPECT": ["JAR_INSPECT"],          # framework friction: addressable by KB
}

# CUSTOMIZE: variant pairs for ΔP delta heatmaps
# Each entry shows how the transition matrix changed from A to B
DELTA_PAIRS = [
    ("simple", "hardened-skills", "Effect of hardened prompt + skills"),
]

# CUSTOMIZE: human-readable labels for each variant (used in findings.md)
NOTE_MAP = {
    "simple":          "Minimal prompt — baseline",
    "hardened-skills":  "Hardened prompt + skills routing",
}

# ---------------------------------------------------------------------------
# CUSTOMIZE: Classifier — maps (tool_name, target) → state name
# ---------------------------------------------------------------------------

def classify_state(tool_name: str, target: str) -> str | None:
    """
    Map a tool call to a semantic state name.

    Return None to exclude the tool call from Markov analysis.
    Return a string from STATES to classify it.

    In DISCOVERY_MODE, returns raw "tool:target" so you can inspect
    frequency counts and define the real taxonomy from actual data.

    This classifier is tuned for Java/Maven/Spring test-writing agents.
    CUSTOMIZE: adjust the Bash subclassification keywords for your domain.
    The overall structure (exclude meta-tools, handle Write/Edit/Read/Glob/Bash)
    applies to any Claude Code agent — only the Bash keywords change.
    """
    tool_lower = tool_name.lower() if tool_name else ""
    target_lower = target.lower() if target else ""

    # Discovery mode: return raw label for frequency inspection
    if DISCOVERY_MODE:
        return f"{tool_name}:{target[:40]}"

    # Exclude meta-tools (task management, agent coordination)
    if tool_lower in ("todowrite", "todoread", "task", "taskupdate", "taskcreate",
                      "exitplanmode", "enterplanmode"):
        return None

    # Skill tool — agent invoking a modular knowledge skill
    if tool_lower == "skill":
        return "READ_SKILL"

    # Agent/Task subagent calls — counts as exploration overhead
    if tool_lower in ("agent", "task"):
        return "EXPLORE"

    # Writing output files (first production)
    # CUSTOMIZE: narrow by file extension to distinguish productive writes
    # e.g., if target_lower.endswith(".java"): return "WRITE" (else EXPLORE for config)
    if tool_lower in ("write", "writefile", "notebookedit"):
        return "WRITE"

    # Editing output files = rework (FIX)
    # CUSTOMIZE: scope to output files only; exclude editing planning docs if needed
    if tool_lower in ("edit", "str_replace_editor", "str_replace_based_edit"):
        return "FIX"

    # Reading files
    if tool_lower in ("read", "readfile"):
        if "knowledge/" in target_lower or "/kb/" in target_lower:
            return "READ_KB"
        # CUSTOMIZE: reading back your own output = VERIFY, reading source = EXPLORE
        return "EXPLORE"

    # Targeted search — agent knows what it's looking for
    if tool_lower in ("glob", "grep"):
        return "EXPLORE"

    # Bash commands — subclassify by what the command is actually doing
    # This is the most domain-specific section. Adjust keywords for your toolchain.
    if tool_lower == "bash":

        # JAR inspection: agent reading .m2 jars to discover classes/imports
        # Signal: agent doesn't know test framework imports — addressable by KB
        # CUSTOMIZE: remove this block for non-Java domains
        if any(x in target_lower for x in (
            "jar tf", "jar -tf", "jar --list",
            "jar xf", "jar -xf",
            "javap ",
        )):
            return "JAR_INSPECT"

        # Build/test execution: the real BUILD state
        # CUSTOMIZE: replace with your build tool (pytest, cargo test, go test, etc.)
        if any(x in target_lower for x in (
            "mvnw clean", "mvnw test", "mvnw verify", "mvnw package",
            "mvnw compile", "mvnw test-compile",
            "gradlew test", "gradlew build", "gradlew check",
            "jacoco:report", "mvn test", "mvn verify",
            "spring-javaformat",
        )):
            return "BUILD"

        # Results verification: reading back output to confirm success
        # CUSTOMIZE: replace with your output format (coverage.xml, test_results/, etc.)
        if any(x in target_lower for x in (
            "jacoco", "index.html", "coverage", "surefire-reports",
        )):
            return "VERIFY"

        # Shell-based exploration: agent searching rather than reading directly
        # Distinct from EXPLORE (Read tool) — agent doesn't know exactly where to look
        if any(x in target_lower for x in (
            "ls ", "find ", "tree ", "cat ", "head ", "tail ", "wc ",
            "grep ", "echo ", "pwd", "dependency:tree", "dep:tree",
        )):
            return "SHELL"

        # Scaffolding (mkdir, cp, mv) — exclude: not semantically interesting
        if any(x in target_lower for x in ("mkdir", "cp ", "mv ", "chmod", "touch ")):
            return None

        # Default bash (sed, awk, curl, etc.) → treat as shell exploration
        return "SHELL"

    return "EXPLORE"  # default


# ---------------------------------------------------------------------------
# Data loading
# ---------------------------------------------------------------------------

def load_data():
    con = duckdb.connect()
    items = con.execute(f"SELECT * FROM '{DATA_DIR}/item_results.parquet'").df()
    tool_uses_path = DATA_DIR / "tool_uses.parquet"
    tools = (con.execute(f"SELECT * FROM '{tool_uses_path}'").df()
             if tool_uses_path.exists() else None)
    con.close()
    return items, tools

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    import time
    t0 = time.time()
    print("Markov Chain Analysis (domain wrapper)")
    print("=" * 50)
    print("\nLoading data...")
    items, tools = load_data()
    if tools is None or tools.empty:
        print("ERROR: No tool_uses.parquet found. Run load_results.py first.")
        raise SystemExit(1)
    print(f"  tool_uses: {len(tools)} rows")
    print(f"  item_results: {len(items)} rows")

    # NOTE: load_results.py already uses the library's expected column names:
    #   item_id (not item_slug), tool_target (not target), global_seq for ordering
    tools = tools.sort_values(["variant", "item_id", "global_seq"])

    pipeline = MarkovAnalysisPipeline(
        classify_fn=classify_state,
        states=STATES,
        output_dir=OUTPUT_DIR,
        analysis_dir=ANALYSIS_DIR,
        colors=COLORS,
        variant_order=VARIANT_ORDER,
        cluster_definitions=CLUSTER_DEFINITIONS,
        delta_pairs=DELTA_PAIRS,
        note_map=NOTE_MAP,
        enable_sankey=True,
    )
    pipeline.run(tools, items)

    # Generate cost/steps bar chart — the "money shot"
    print("\nGenerating cost vs steps bar chart...")
    import matplotlib.pyplot as plt
    import matplotlib
    matplotlib.use("Agg")
    import duckdb
    con = duckdb.connect()
    cost_df = con.execute(f"""
        SELECT variant,
               round(avg(cost_usd), 4)      AS mean_cost,
               round(stddev(cost_usd), 4)   AS std_cost,
               round(avg(phase_count), 1)   AS mean_steps
        FROM '{DATA_DIR}/item_results.parquet'
        GROUP BY variant ORDER BY variant
    """).df()
    con.close()
    ordered = [v for v in VARIANT_ORDER if v in cost_df["variant"].values]
    cost_df = cost_df.set_index("variant").loc[ordered].reset_index()

    fig, axes = plt.subplots(1, 2, figsize=(12, 5))
    plt.rcParams.update({"font.family": "serif", "font.size": 10})

    bar_colors = ["#C44E52" if v == VARIANT_ORDER[-1] else "#4C72B0"
                  for v in cost_df["variant"]]

    ax = axes[0]
    bars = ax.bar(range(len(cost_df)), cost_df["mean_cost"], color=bar_colors,
                  yerr=cost_df["std_cost"], capsize=4, edgecolor="white", linewidth=0.5)
    ax.set_xticks(range(len(cost_df)))
    ax.set_xticklabels(cost_df["variant"], rotation=35, ha="right", fontsize=9)
    ax.set_ylabel("Mean cost per item (USD)", fontsize=11)
    ax.set_title("Cost per variant", fontsize=12, fontweight="bold")
    ax.yaxis.grid(True, alpha=0.3)
    ax.set_axisbelow(True)
    for bar, cost in zip(bars, cost_df["mean_cost"]):
        ax.text(bar.get_x() + bar.get_width()/2, bar.get_height() + 0.02,
                f"${cost:.2f}", ha="center", va="bottom", fontsize=8)

    ax = axes[1]
    bars2 = ax.bar(range(len(cost_df)), cost_df["mean_steps"], color=bar_colors,
                   edgecolor="white", linewidth=0.5)
    ax.set_xticks(range(len(cost_df)))
    ax.set_xticklabels(cost_df["variant"], rotation=35, ha="right", fontsize=9)
    ax.set_ylabel("Mean steps per item", fontsize=11)
    ax.set_title("Steps per variant", fontsize=12, fontweight="bold")
    ax.yaxis.grid(True, alpha=0.3)
    ax.set_axisbelow(True)
    for bar, steps in zip(bars2, cost_df["mean_steps"]):
        ax.text(bar.get_x() + bar.get_width()/2, bar.get_height() + 0.5,
                f"{steps:.0f}", ha="center", va="bottom", fontsize=8)

    fig.suptitle("Agent efficiency by variant — cost and step count", fontsize=13)
    fig.tight_layout()
    out = OUTPUT_DIR / "cost-steps-comparison"
    fig.savefig(str(out) + ".png", dpi=150, bbox_inches="tight")
    fig.savefig(str(out) + ".pdf", bbox_inches="tight")
    plt.close(fig)
    print(f"  {out}.png")

    # Generate individual per-variant heatmaps (large, readable — for docs/reports)
    print("\nGenerating individual variant heatmaps...")
    classified = apply_classify(tools, classify_state)
    count_matrices = build_transition_counts(classified, STATES)
    for variant in VARIANT_ORDER:
        if variant not in count_matrices:
            continue
        P = normalize_to_probability_matrix(count_matrices[variant])
        safe_name = variant.replace("+", "-").replace(" ", "_")
        out_path = OUTPUT_DIR / f"heatmap-{safe_name}"
        make_single_transition_matrix(P, STATES, variant, out_path)
        print(f"  {out_path}.png")

    elapsed = time.time() - t0
    print(f"\nDone in {elapsed:.1f}s")
    print(f"  Figures:      {OUTPUT_DIR}")
    print(f"  Summary:      {ANALYSIS_DIR / 'markov-findings.md'}")
