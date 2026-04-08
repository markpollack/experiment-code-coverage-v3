# Design: Code Coverage Experiment v3

## Evaluation Architecture

> This project is a consumer of `agent-experiment`.
> See `~/projects/agent-experiment/plans/DESIGN.md` for the framework.

### Consumer Integration

| Integration Point | This Project Provides | Framework Provides |
|---|---|---|
| Agent invocation | `CoverageAgentInvoker` | ExperimentRunner orchestration |
| Tier 0 judge | BuildSuccessJudge (./mvnw test) | CascadedJury cascading |
| Tier 1 judge | JaCoCo coverage judge (TODO) | TierPolicy (ACCEPT_ON_ALL_PASS) |
| Tier 2 judge | Practice adherence LLM judge | CascadedJury FINAL_TIER policy |
| Dataset | petclinic-partial in items.yaml | DatasetManager, workspace isolation |
| Results | Domain analysis + comparison | ResultStore, ExperimentResult |

### Judges

| Judge | Tier | Type | Evaluates |
|-------|------|------|-----------|
| BuildSuccessJudge | 0 (guardrail) | Deterministic | `./mvnw clean test` exits 0 |
| JaCoCo coverage (TODO) | 1 (grader) | Deterministic | Line coverage >= 85% (NumericalScore 0-1) |
| Practice adherence | 2 (semantic) | LLM | 6-criteria rubric with grep enforcement |

### T2 Judge Criteria

1. Test Slice Selection (0.0-1.0)
2. Assertion Quality (0.0-1.0)
3. Error/Edge Case Coverage (0.0-1.0)
4. Domain-Specific Patterns — flush/clear, TestEntityManager (0.0-1.0)
5. Coverage Target Selection (0.0-1.0)
6. Version-Aware Patterns — MockMvcTester vs MockMvc (0.0-1.0)

### Convergence Criteria

- T0 (build success): 100% pass rate
- T1 (coverage): >= 85% line coverage
- T2 (quality): >= 0.80 average across 6 criteria

### Experiment Variants

| Variant | What Changes | What's Constant | Hypothesis |
|---------|-------------|-----------------|-----------|
| simple | 2-line prompt, no explicit skills | Dataset, model, skills availability | Coverage converges at model floor (~91%) |
| hardened-skills | 7-step prompt, doubt-creating skill descriptions | Dataset, model, skills availability | Same coverage, potentially higher T2 quality |

### Prior Results (from agentworks-validation)

| Metric | Simple (N=3) | Hardened+Skills (N=3) |
|--------|-------------|----------------------|
| Coverage | 90-91% | 90-91% |
| T2 avg (6-criteria) | 0.67 | 0.67 |
| T2 avg (MockMvcTester exemplar) | — | 0.82 |
| Skills invoked | 1.0 avg | 2.3 avg |

### Dataset

Single item: `spring-petclinic-partial`
- Source: `/home/mark/projects/spring-petclinic-partial`
- Boot version: 4.0.1 (Spring Framework 7.0.2)
- Baseline: 56% line coverage (11 of 17 test files deleted)
- 6 seed files remain (OwnerControllerTests, VetControllerTests, ClinicServiceTests, CrashControllerTests, ValidatorTests, EntityUtils)

### Skills (installed globally, not per-variant)

6 spring-testing skills in `~/.claude/skills/`:
- spring-mvc-testing, spring-jpa-testing, spring-testing-fundamentals
- spring-security-testing, spring-webflux-testing, spring-websocket-testing

All have doubt-creating L1 descriptions that trigger loading from any prompt.
