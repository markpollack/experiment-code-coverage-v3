# Vision: Code Coverage Experiment v3

## Problem Statement

When an LLM agent writes tests for an existing project, what controls the quality of the output? Prior work (agentworks-validation) showed that skills fire from descriptions alone, coverage converges regardless of approach, and exemplars override skill guidance. This experiment formalizes those findings into a reproducible, automated benchmark.

## Success Criteria

1. **Reproduce baseline**: simple and hardened-skills variants both reach 90-91% coverage, 0.67 T2 avg (validates methodology matches manual runs)
2. **Automate the jury**: 3-tier CascadedJury (build → coverage → quality) runs without manual intervention
3. **Confirm exemplar effect**: With MockMvcTester exemplar, criterion 6 reaches 1.0; without, 0.3
4. **N=3 per variant**: Statistical confidence via sweep runs

## Scope

**In scope**:
- spring-petclinic-partial dataset (single item)
- 2 variants: simple, hardened-skills
- 3-tier jury: T0 build, T1 JaCoCo, T2 practice adherence
- N=3 sweeps per variant
- Comparison reports

**Out of scope**:
- Multiple dataset items (gs-rest-service, etc.)
- Knowledge base variants (ablation studies)
- SAE / forge variants
- Markov trace analysis (covered by code-coverage-experiment v1)

## Unknowns

- Can the T2 LLM judge (practice adherence) be reliably automated via CascadedJury, or does it need manual invocation via run-judge.sh?
- Does the agent-experiment framework's workspace isolation correctly handle petclinic's Maven wrapper?

## Assumptions

- spring-petclinic-partial at commit 93e4e1b is stable and reproducible
- Claude Sonnet 4.6 is available and pricing is stable
- Agent-experiment framework dependencies resolve from local Maven cache
