# Roadmap: Code Coverage Experiment v3

## Stage 1: Consumer Scaffolding

### Step 1.0: Design review — DONE
- [x] Read agent-experiment DESIGN docs
- [x] Read agent-experiment-template structure
- [x] Read agentworks-validation experiment findings

### Step 1.1: Project scaffolding — DONE
- [x] Maven project with pom.xml, mvnw, directories
- [x] Prompts externalized (v0-simple.txt, v1-hardened.txt)
- [x] experiment-config.yaml with 2 variants
- [x] Dataset: items.yaml with petclinic-partial
- [x] Judge prompt: judge-practice-adherence.txt
- [x] run-judge.sh script
- [x] CLAUDE.md, VISION.md, DESIGN.md
- [x] git init

### Step 1.2: Verify compile — TODO
- [ ] `./mvnw compile` succeeds
- [ ] All framework dependencies resolve
- [ ] Create: `plans/learnings/step-1.2-compile.md`

### Step 1.3: Wire CoverageAgentInvoker — TODO
- [ ] Replace placeholder with real AgentClient invocation
- [ ] Test with `--variant simple --item spring-petclinic-partial`
- [ ] Verify workspace isolation works with Maven wrapper
- [ ] Create: `plans/learnings/step-1.3-invoker.md`

### Step 1.4: T1 judge — JaCoCo coverage — TODO
- [ ] Implement deterministic judge that parses JaCoCo HTML report
- [ ] Score: line coverage as NumericalScore (0.0–1.0)
- [ ] Wire into JuryFactory at tier 1

### Step 1.5: T2 judge — Practice adherence — TODO
- [ ] Wire LLM judge using judge-practice-adherence.txt prompt
- [ ] Parse JSON output into 6 NumericalScore values
- [ ] Wire into JuryFactory at tier 2

### Step 1.6: SmokeTest — TODO
- [ ] Integration test validating full wiring (placeholder invoker)
- [ ] `./mvnw test` passes
- [ ] Create: `plans/learnings/step-1.6-smoke.md`

### Step 1.K: Stage 1 consolidation — TODO
- [ ] Compact learnings → `plans/learnings/LEARNINGS.md`
- [ ] Update CLAUDE.md

## Stage 2: Control Baseline

### Step 2.0: Stage 2 entry — TODO
- [ ] Read Stage 1 `plans/learnings/LEARNINGS.md`

### Step 2.1: Simple variant — N=1 — TODO
- [ ] Run `--variant simple`
- [ ] Verify T0 pass, T1 coverage, T2 scores
- [ ] Compare with agentworks-validation baseline (expect ~91% coverage, ~0.67 T2)

### Step 2.2: Hardened-skills variant — N=1 — TODO
- [ ] Run `--variant hardened-skills`
- [ ] Compare with simple variant
- [ ] Run comparison report

### Step 2.K: Stage 2 consolidation — TODO
- [ ] Compact learnings
- [ ] Update CLAUDE.md

## Stage 3: N=3 Sweeps

### Step 3.0: Stage 3 entry — TODO
- [ ] Read Stage 2 summary

### Step 3.1: Simple sweep — N=3 — TODO
- [ ] `--run-all-variants --sweep simple-n3` (3 runs)
- [ ] Verify consistency across runs

### Step 3.2: Hardened-skills sweep — N=3 — TODO
- [ ] `--run-all-variants --sweep hardened-n3` (3 runs)
- [ ] Compare with simple sweep

### Step 3.3: Analysis — TODO
- [ ] Comparison report across all 6 runs
- [ ] Confirm model floor, partial knowledge paradox
- [ ] Archive results

### Step 3.K: Stage 3 consolidation — TODO
- [ ] Final learnings
- [ ] Update CLAUDE.md
