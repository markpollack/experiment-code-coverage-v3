# Code Coverage Experiment v3

Evaluates how agent skills, prompt structure, and seed file exemplars affect test quality when an LLM agent writes JUnit tests for Spring Boot 4 projects.

**Consumer of**: `agent-experiment` (io.github.markpollack:experiment-core)
**Dataset**: spring-petclinic-partial (56% baseline, Boot 4.0.1, commit 93e4e1b)
**Agent**: Claude Code (claude-sonnet-4-6)
**Key finding**: Existing test files (exemplars) override skill guidance — the partial knowledge paradox.

## Quick Start

```bash
# Run a single variant
./mvnw compile exec:java -Dexec.args="--variant simple"

# Run all variants
./mvnw compile exec:java -Dexec.args="--run-all-variants"

# Run with item filter
./mvnw compile exec:java -Dexec.args="--variant simple --item spring-petclinic-partial"

# Judge a workspace
./scripts/run-judge.sh results/<session>/spring-petclinic-partial
```

## Architecture

```
agent-experiment (orchestration)
  ├── ExperimentRunner   — dataset → workspace → invoke → judge → persist
  ├── CascadedJury       — 3-tier: T0 build → T1 coverage → T2 quality
  └── ResultStore        — experiment results with verdict traces
         │
         │ this project provides:
         │   CoverageAgentInvoker + 3-tier jury + petclinic dataset
         │
    experiment-code-coverage-v3
```

## Source Material Routing

| Document | Path | Read when... |
|----------|------|-------------|
| VISION.md | `plans/VISION.md` | Always read first |
| DESIGN.md | `plans/DESIGN.md` | Before implementation |
| Experiment findings | `plans/experiment-findings.md` | Understanding prior results |
| agent-experiment DESIGN | `~/projects/agent-experiment/plans/DESIGN.md` | Understanding framework |

## Variants

| Variant | Prompt | Skills | Hypothesis |
|---------|--------|--------|-----------|
| simple | v0-simple.txt (2 lines) | None in prompt, but skills available via L1 descriptions | Baseline — model capability alone |
| hardened-skills | v1-hardened.txt (7-step) | 6 spring-testing skills with doubt-creating descriptions | Structured execution + skills |

## Judges

| Tier | Judge | Type | Policy |
|------|-------|------|--------|
| T0 | BuildSuccessJudge | Deterministic | REJECT_ON_ANY_FAIL |
| T1 | JaCoCo coverage (TODO) | Deterministic | ACCEPT_ON_ALL_PASS |
| T2 | Practice adherence | LLM (6-criteria) | FINAL_TIER |

## Key Files

| File | Purpose |
|------|---------|
| `experiment-config.yaml` | Variant definitions |
| `dataset/items.yaml` | Benchmark dataset |
| `prompts/v0-simple.txt` | 2-line simple prompt |
| `prompts/v1-hardened.txt` | 7-step structured prompt |
| `prompts/judge-practice-adherence.txt` | 6-criteria T2 judge with grep enforcement |
| `scripts/run-judge.sh` | Judge runner script |

## Not Covered

- Framework orchestration — that's `agent-experiment`
- Judge framework internals — that's `agent-judge`
- Skills content — that's `spring-testing-skills`
- Research synthesis — that's `tuvium-research-conversation-agent`
