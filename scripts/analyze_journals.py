#!/usr/bin/env python3
"""
Journal analysis — extract behavioral insights from agent tool-call traces.

Answers 6 questions about agent behavior from experiment result JSON files:

  Q1. Skill consumption trace — did the agent read a skill and then ignore it?
  Q2. Tool call classification — READ_PROD, READ_TEST, WRITE_TEST, etc.
  Q3. Write-fail-fix cycles — how many rework loops per run?
  Q4. File read order — behavioral fingerprint (what does the agent read first?)
  Q5. Token/cost distribution by phase — orientation vs generation vs verification
  Q6. Redundant reads — files read more than once per run

Reads from: results/{experiment}/sessions/{session}/{variant}.json
Requires: load_results.py to have been run first (for parquet), but can also
          read directly from session JSON files.

Usage:
    python scripts/analyze_journals.py --experiment code-coverage-v3 \
        --session 20260409-012242 --session 20260409-021439 --session 20260409-031243

    # Or use latest sessions automatically:
    python scripts/analyze_journals.py --experiment code-coverage-v3 --last 3
"""

import argparse
import json
from collections import Counter, defaultdict
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent

# ---------------------------------------------------------------------------
# CUSTOMIZE: Skill name(s) to track for the consumption trace (Q1)
# ---------------------------------------------------------------------------
TRACKED_SKILLS = ["spring-mvc-testing", "spring-jpa-testing"]

# CUSTOMIZE: What pattern the skill teaches vs what the agent writes instead
SKILL_TEACHES = "MockMvcTester"       # the new pattern the skill recommends
AGENT_WRITES  = "mockMvc.perform"     # the old pattern the agent uses anyway

# CUSTOMIZE: Reference file(s) that the skill points to
SKILL_REFERENCE_MARKERS = ["references/", "mvc-rest", "jpa-testing"]

# ---------------------------------------------------------------------------
# CUSTOMIZE: Tool call classification for Q2
# ---------------------------------------------------------------------------

def classify_tool(name: str, inp: dict) -> str | None:
    """Classify a tool call into a behavioral category.

    Returns None to exclude meta-tools (TodoWrite, etc.) from analysis.
    """
    target = inp.get("file_path", inp.get("path", inp.get("command", inp.get("skill", ""))))
    tl = (target or "").lower()
    nl = name.lower()

    # Exclude meta-tools
    if nl in ("todowrite", "todoread"):
        return None

    # Skills
    if nl == "skill":
        return "READ_SKILL"

    # Subagent exploration
    if nl in ("task", "taskoutput", "glob", "grep"):
        return "EXPLORE_DIR"

    # Reading files
    if nl in ("read", "readfile"):
        if any(m in tl for m in SKILL_REFERENCE_MARKERS):
            return "READ_SKILL"
        if "jacoco" in tl or "index.html" in tl or "surefire" in tl:
            return "RUN_COVERAGE"
        # Heuristic: files with "Test" in name = reading tests
        fname = target.split("/")[-1] if "/" in (target or "") else (target or "")
        if "Test" in fname:
            return "READ_TEST"
        return "READ_PROD"

    # Writing / editing test files
    if nl in ("write", "writefile", "notebookedit"):
        return "WRITE_TEST"
    if nl in ("edit", "str_replace_editor", "str_replace_based_edit"):
        return "WRITE_TEST"

    # Bash commands — subclassify
    if nl == "bash":
        # Build / compile / test execution
        if any(x in tl for x in (
            "mvnw clean", "mvnw test", "mvnw compile", "mvnw verify",
            "mvnw package", "mvnw test-compile", "mvnw surefire",
            "gradlew test", "gradlew build", "gradlew check",
            "spring-javaformat", "jacoco:report",
        )):
            return "RUN_BUILD"
        # Coverage / results verification
        if any(x in tl for x in ("jacoco", "index.html", "coverage", "surefire")):
            return "RUN_COVERAGE"
        # Shell exploration
        if any(x in tl for x in (
            "find ", "ls ", "tree ", "cat ", "head ", "tail ",
            "wc ", "grep ", "awk ", "python3",
        )):
            return "EXPLORE_DIR"
        return "OTHER"

    return "OTHER"


# ---------------------------------------------------------------------------
# Data loading
# ---------------------------------------------------------------------------

def load_runs(results_dir: Path, session_names: list[str]) -> list[dict]:
    """Load all runs from the given sessions.

    Returns list of dicts with keys: sweep, variant, tools, results, item.
    """
    runs = []
    for i, session_name in enumerate(session_names, start=1):
        session_dir = results_dir / "sessions" / session_name
        if not session_dir.exists():
            print(f"WARNING: session not found: {session_dir}")
            continue
        for f in sorted(session_dir.glob("*.json")):
            if f.name in ("session.json", "sessions-index.json"):
                continue
            with open(f) as fh:
                data = json.load(fh)
            item = data["items"][0]
            phases = item.get("invocationResult", {}).get("phases", [])
            if not phases:
                continue
            phase = phases[0]
            runs.append({
                "sweep": f"n{i}",
                "variant": f.stem,
                "tools": phase.get("toolUses", []),
                "results": phase.get("toolResults", []),
                "item": item,
                "phase": phase,
            })
    return runs


def find_latest_sessions(results_dir: Path, count: int) -> list[str]:
    """Find the N most recent session directories."""
    sessions_dir = results_dir / "sessions"
    if not sessions_dir.exists():
        return []
    sessions = sorted(sessions_dir.iterdir(), reverse=True)
    return [s.name for s in sessions[:count]][::-1]  # chronological order


# ---------------------------------------------------------------------------
# Q1: Skill consumption trace
# ---------------------------------------------------------------------------

def analyze_skill_consumption(runs: list[dict]):
    print("=" * 70)
    print("Q1: SKILL CONSUMPTION — agent read skill then wrote old patterns?")
    print("=" * 70)

    found_any = False
    for run in runs:
        tools = run["tools"]
        skill_seq = None
        ref_seq = None

        for i, t in enumerate(tools):
            name = t["name"]
            inp = t.get("input", {})

            # Find skill invocation
            if name == "Skill" and any(s in inp.get("skill", "") for s in TRACKED_SKILLS):
                skill_seq = i

            # Find reference file read after skill
            if skill_seq is not None and ref_seq is None and name == "Read":
                fp = inp.get("file_path", "")
                if any(m in fp for m in SKILL_REFERENCE_MARKERS):
                    ref_seq = i

            # Find first test Write after reference read
            if ref_seq is not None and name == "Write":
                fp = inp.get("file_path", "")
                if "Test" in fp:
                    content = inp.get("content", "")
                    has_old = AGENT_WRITES in content
                    has_new = SKILL_TEACHES in content
                    fname = fp.split("/")[-1]

                    found_any = True
                    print(f"\n{run['sweep']}/{run['variant']}:")
                    print(f"  [{skill_seq:2d}] Skill({tools[skill_seq]['input'].get('skill', '')})")
                    print(f"  [{ref_seq:2d}] Read(reference doc)")
                    print(f"  [{i:2d}] Write({fname})")
                    print(f"  -> {AGENT_WRITES}: {has_old}  <- OLD pattern")
                    print(f"  -> {SKILL_TEACHES}:     {has_new}  <- skill taught this")
                    break

    if not found_any:
        print("\n  No skill consumption sequences found.")


# ---------------------------------------------------------------------------
# Q2: Tool call classification
# ---------------------------------------------------------------------------

def analyze_tool_classification(runs: list[dict]):
    print("\n" + "=" * 70)
    print("Q2: TOOL CALL CLASSIFICATION — counts and percentages per variant")
    print("=" * 70)

    variant_counts: dict[str, Counter] = defaultdict(Counter)
    variant_totals: dict[str, int] = defaultdict(int)

    for run in runs:
        variant = run["variant"]
        for t in run["tools"]:
            cat = classify_tool(t["name"], t.get("input", {}))
            if cat:
                variant_counts[variant][cat] += 1
                variant_totals[variant] += 1

    categories = ["READ_PROD", "READ_TEST", "READ_SKILL", "WRITE_TEST",
                   "RUN_BUILD", "RUN_COVERAGE", "EXPLORE_DIR", "OTHER"]
    variants = sorted(variant_totals.keys())

    header = f"{'Category':16s}"
    for v in variants:
        header += f" | {v:>10s}  {'%':>5s}"
    print(f"\n{header}")
    print("-" * len(header))

    for cat in categories:
        row = f"{cat:16s}"
        for v in variants:
            n = variant_counts[v][cat]
            pct = 100 * n / variant_totals[v] if variant_totals[v] else 0
            row += f" | {n:10d}  {pct:5.1f}%"
        print(row)

    row = f"{'TOTAL':16s}"
    for v in variants:
        row += f" | {variant_totals[v]:10d}       "
    print(row)


# ---------------------------------------------------------------------------
# Q3: Write-fail-fix cycles
# ---------------------------------------------------------------------------

def analyze_fix_cycles(runs: list[dict]):
    print("\n" + "=" * 70)
    print("Q3: WRITE-FAIL-FIX CYCLES — per variant")
    print("=" * 70)

    variant_cycles: dict[str, list[int]] = defaultdict(list)

    for run in runs:
        tools = run["tools"]
        results = run["results"]
        written_files: set[str] = set()
        last_build_failed = False
        cycles = 0

        for i, t in enumerate(tools):
            name = t["name"]
            inp = t.get("input", {})

            if name == "Write":
                fp = (inp.get("file_path", "") or "").split("/")[-1]
                if "Test" in fp:
                    written_files.add(fp)

            if name == "Bash":
                cmd = (inp.get("command", "") or "").lower()
                if any(x in cmd for x in ("mvnw test", "mvnw compile", "mvnw clean")):
                    if i < len(results):
                        r = results[i].get("content", "")
                        last_build_failed = ("FAILURE" in r or "ERROR" in r
                                             or "error" in r[:500].lower())

            if name == "Edit" and last_build_failed:
                fp = (inp.get("file_path", "") or "").split("/")[-1]
                if fp in written_files:
                    cycles += 1
                    last_build_failed = False

        variant_cycles[run["variant"]].append(cycles)

    for variant in sorted(variant_cycles.keys()):
        counts = variant_cycles[variant]
        total = sum(counts)
        avg = total / len(counts) if counts else 0
        print(f"  {variant}: {total} cycles across {len(counts)} runs (avg {avg:.1f}/run)")


# ---------------------------------------------------------------------------
# Q4: File read order — behavioral fingerprint
# ---------------------------------------------------------------------------

def analyze_read_order(runs: list[dict], max_reads: int = 15):
    print("\n" + "=" * 70)
    print("Q4: FILE READ ORDER — behavioral fingerprint (first run per variant)")
    print("=" * 70)

    seen_variants: set[str] = set()
    for run in runs:
        variant = run["variant"]
        if variant in seen_variants:
            continue
        seen_variants.add(variant)

        tools = run["tools"]
        print(f"\n{variant} ({run['sweep']}):")
        count = 0
        for i, t in enumerate(tools):
            if t["name"] in ("Read", "Skill") and count < max_reads:
                inp = t.get("input", {})
                fp = inp.get("file_path", inp.get("skill", ""))
                fname = fp.split("/")[-1] if "/" in fp else fp
                print(f"  [{i:2d}] {t['name']:6s} {fname}")
                count += 1


# ---------------------------------------------------------------------------
# Q5: Token distribution by phase
# ---------------------------------------------------------------------------

def analyze_token_distribution(runs: list[dict]):
    print("\n" + "=" * 70)
    print("Q5: COST & TOKEN DISTRIBUTION")
    print("=" * 70)

    for variant in sorted({r["variant"] for r in runs}):
        v_runs = [r for r in runs if r["variant"] == variant]
        total_tools = sum(len(r["tools"]) for r in v_runs)
        total_cost = sum(r["item"].get("costUsd", 0) for r in v_runs)
        total_dur = sum(r["item"].get("durationMs", 0) for r in v_runs) / 1000

        # Count tools by phase: orientation (before first Write), generation, verification
        orient_counts = []
        gen_counts = []
        verify_counts = []

        for run in v_runs:
            tools = run["tools"]
            first_write = None
            last_write = None
            for i, t in enumerate(tools):
                if t["name"] in ("Write", "Edit"):
                    if first_write is None:
                        first_write = i
                    last_write = i

            first_write = first_write or len(tools)
            last_write = last_write or len(tools)
            orient_counts.append(first_write)
            gen_counts.append(last_write - first_write)
            verify_counts.append(len(tools) - last_write)

        avg_tools = total_tools / len(v_runs)
        avg_orient = sum(orient_counts) / len(orient_counts)
        avg_gen = sum(gen_counts) / len(gen_counts)
        avg_verify = sum(verify_counts) / len(verify_counts)

        print(f"\n{variant} ({len(v_runs)} runs):")
        print(f"  avg tool calls: {avg_tools:.0f}")
        print(f"  avg cost:       ${total_cost / len(v_runs):.2f}")
        print(f"  avg duration:   {total_dur / len(v_runs):.0f}s")
        print(f"  orientation:    {avg_orient:.0f} calls ({100*avg_orient/avg_tools:.0f}%)"
              f"  — before first Write")
        print(f"  generation:     {avg_gen:.0f} calls ({100*avg_gen/avg_tools:.0f}%)"
              f"  — first Write to last Write")
        print(f"  verification:   {avg_verify:.0f} calls ({100*avg_verify/avg_tools:.0f}%)"
              f"  — after last Write")


# ---------------------------------------------------------------------------
# Q6: Redundant reads
# ---------------------------------------------------------------------------

def analyze_redundant_reads(runs: list[dict]):
    print("\n" + "=" * 70)
    print("Q6: REDUNDANT READS — files read more than once per run")
    print("=" * 70)

    variant_redundant: dict[str, list[int]] = defaultdict(list)
    shown_variants: set[str] = set()

    for run in runs:
        variant = run["variant"]
        read_counts: Counter = Counter()
        for t in run["tools"]:
            if t["name"] == "Read":
                fp = t.get("input", {}).get("file_path", "")
                parts = fp.rstrip("/").split("/")
                fname = "/".join(parts[-2:]) if len(parts) > 1 else fp
                read_counts[fname] += 1

        redundant = sum(v - 1 for v in read_counts.values() if v > 1)
        variant_redundant[variant].append(redundant)

        # Show details for first run of each variant
        if variant not in shown_variants:
            shown_variants.add(variant)
            dupes = {k: v for k, v in read_counts.items() if v > 1}
            if dupes:
                print(f"\n{variant} ({run['sweep']}) — {redundant} redundant reads:")
                for f, c in sorted(dupes.items(), key=lambda x: -x[1]):
                    print(f"  {c}x {f}")

    for variant in sorted(variant_redundant.keys()):
        counts = variant_redundant[variant]
        avg = sum(counts) / len(counts) if counts else 0
        print(f"\n{variant} avg: {avg:.1f} redundant reads/run")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        description="Analyze agent journals from experiment results")
    parser.add_argument("--experiment", required=True,
                        help="Experiment name (subdirectory of results/)")
    parser.add_argument("--results-dir", type=Path, default=None,
                        help="Override results directory")
    parser.add_argument("--session", action="append", dest="sessions",
                        help="Session name(s). Repeatable. Pass in chronological order.")
    parser.add_argument("--last", type=int, default=None,
                        help="Use the N most recent sessions (alternative to --session)")
    args = parser.parse_args()

    results_dir = args.results_dir or (PROJECT_ROOT / "results" / args.experiment)

    if args.sessions:
        session_names = args.sessions
    elif args.last:
        session_names = find_latest_sessions(results_dir, args.last)
        if not session_names:
            print(f"ERROR: No sessions found in {results_dir / 'sessions'}")
            raise SystemExit(1)
        print(f"Using {len(session_names)} most recent sessions: {session_names}")
    else:
        session_names = find_latest_sessions(results_dir, 3)
        if not session_names:
            print(f"ERROR: No sessions found in {results_dir / 'sessions'}")
            raise SystemExit(1)
        print(f"Using {len(session_names)} most recent sessions: {session_names}")

    print(f"Loading from: {results_dir}")
    runs = load_runs(results_dir, session_names)
    print(f"Loaded {len(runs)} runs across {len(session_names)} sessions")

    if not runs:
        print("ERROR: No runs found")
        raise SystemExit(1)

    analyze_skill_consumption(runs)
    analyze_tool_classification(runs)
    analyze_fix_cycles(runs)
    analyze_read_order(runs)
    analyze_token_distribution(runs)
    analyze_redundant_reads(runs)


if __name__ == "__main__":
    main()
