# Workshop Experiment Findings: Skills, Exemplars, and the Partial Knowledge Paradox

> **Project**: agentworks-validation
> **Period**: 2026-04-07 through 2026-04-08
> **Target**: Spring I/O Barcelona 2-hour workshop, mid-April 2026
> **Task**: Agent adds JUnit tests to Spring PetClinic (56% baseline, target 85%+)

---

## Executive Summary

We ran 15+ experiment variants to understand what controls test quality when an LLM agent writes JUnit tests for a Spring Boot 4 project. Three findings emerged:

1. **Skills fire from descriptions alone** — no router, no prompt steering needed. Doubt-creating YAML descriptions ("your training data gets this wrong") trigger skill loading even with a 2-line prompt.

2. **Coverage converges regardless of approach** — PetClinic reaches 90-91% whether the agent has a 2-line prompt or a structured 7-step process with skills. This is the model floor.

3. **The exemplar controls quality, not the skill** — the agent mimics existing test files over skill guidance. When seed tests use `MockMvc.perform()`, the agent writes `MockMvc.perform()` everywhere. When seed tests use `MockMvcTester`, the agent writes `MockMvcTester` everywhere. Same skills, same prompt. Only the exemplar changed. Criterion 6 (version-aware patterns) jumped from 0.3 to 1.0.

---

## Experimental Setup

### The Task

Spring PetClinic (Boot 4.0.1, Spring Framework 7.0.2) with 11 of 17 test files deleted. 6 seed files remain at 56% line coverage. The agent must add tests to reach 85%+.

**Seed files kept** (one exemplar per test type):
- `OwnerControllerTests` — @WebMvcTest with MockMvc (later migrated to MockMvcTester)
- `VetControllerTests` — @WebMvcTest with MockMvc (later migrated to MockMvcTester)
- `ClinicServiceTests` — @DataJpaTest (no flush/clear)
- `CrashControllerTests` — plain JUnit
- `ValidatorTests` — plain JUnit with LocalValidatorFactoryBean
- `EntityUtils` — test utility class

**Baseline coverage by package**:
| Package | Coverage |
|---------|----------|
| owner | 49% |
| system | 59% |
| vet | 100% |
| model | 98% |
| **Total** | **56%** |

### Prompt Variants

**v0-simple** (2 lines):
```
Add tests to improve code coverage. Target: 85% line coverage.
```

**v1-hardened** (7-step structured process):
- Read pom.xml for Boot version
- Read all existing tests
- Run JaCoCo to identify gaps
- Read production code for uncovered packages
- Write tests targeting largest gaps
- Verify compilation + tests after each batch
- Iterate until 85% met

### Skills

6 spring-testing skills installed via SkillsJar to `~/.claude/skills/`:
- `spring-mvc-testing` — MockMvcTester patterns, @WebMvcTest setup
- `spring-jpa-testing` — flush/clear, TestEntityManager
- `spring-testing-fundamentals` — @MockitoBean, AssertJ/BDDMockito idioms
- `spring-security-testing` — @WithMockUser, CSRF, JWT
- `spring-webflux-testing` — WebTestClient, StepVerifier
- `spring-websocket-testing` — STOMP, async assertions

### Judge

T2 LLM judge (Sonnet) scoring 6 criteria (0.0-1.0 each):
1. Test Slice Selection
2. Assertion Quality
3. Error/Edge Case Coverage
4. Domain-Specific Patterns (flush/clear, TestEntityManager)
5. Coverage Target Selection
6. Version-Aware Patterns (MockMvcTester vs MockMvc)

---

## Phase 1: The Router Problem (v1-v5)

### What we tried

The original skills architecture used a **router skill** — a SKILL.md file containing a routing table that mapped testing domains to specific skills. The agent was expected to read the router, then follow through to domain skills.

| Run | Architecture | Skills Read | Domain Skills Consumed | Result |
|-----|-------------|-------------|----------------------|--------|
| v2 | Router + 6 domain skills | 6 | 3 (router + MVC + fundamentals) | Agent followed the chain |
| v3 | Router with Critical Rules in SKILL.md | 1 | 0 (router only) | Agent stopped at router |
| v4 | Router with CRITICAL warning | 1 | 0 (router only) | Agent stopped at router |
| v5 | Prompt says "invoke spring-mvc-testing" | 5 | 2 (MVC + fundamentals, no router) | Agent bypassed router |

### What we learned

**The router is a single point of failure.** When the agent decides it doesn't need what the router offers, the entire skill chain collapses. v3/v4 read the router, decided they already knew Spring testing, and never opened the domain skills where the actual patterns live.

**Discovery vs injection**: The router is a discovery mechanism — it tells the agent what knowledge exists. It doesn't force consumption. An agent that believes it already knows Spring JPA testing will skip the skill, regardless of what the router says.

**Prompt steering works but doesn't scale**: v5 added explicit instructions ("Before writing any @WebMvcTest: invoke spring-mvc-testing"). The agent consumed identical reference material as v2 (~41KB across 3 files) but via a direct path, bypassing the router entirely.

### Trace evidence

The three navigation paths observed:
```
v2 (worked):  pom.xml → agent initiative → router → 2 domain skills → 3 reference files
v5 (worked):  pom.xml → prompt instruction → 2 domain skills → 3 reference files
v3/v4 (failed): pom.xml → agent initiative → router → STOP
```

---

## Phase 2: Kill the Router, Write Doubt-Creating Descriptions (v6-v7)

### The insight

Claude Code's built-in skill system already IS a router. It reads YAML `description` fields at startup and suggests skills based on token matching. Building a second router on top is redundant L2 routing that requires agent judgment (which fails).

The agentskills.io spec defines 3-level progressive disclosure:
- **L1**: Metadata (~100 tokens/skill, always loaded) — the YAML description
- **L2**: Instructions (SKILL.md body, <5K tokens, loaded when triggered)
- **L3**: Resources (reference files, unlimited, loaded as needed)

Claude Code's L1 system handles routing. We just need descriptions that make the agent want to read more.

### What we changed

**Deleted** the router skill entirely.

**Rewrote** all 6 skill descriptions to create doubt — exploiting the gap between model training cutoff and Boot 4's release:

Before (capability listing):
```yaml
description: "Patterns for @WebMvcTest with MockMvc, REST endpoints, jsonPath assertions"
```

After (doubt-creating):
```yaml
description: "Boot 4+ replaced MockMvc assertions with MockMvcTester — incompatible API
your training data gets wrong. Read before writing any @WebMvcTest."
```

### Results: v6 (with prompt steering) vs v7 (without)

| Metric | v6 (steering) | v7 (no steering) |
|--------|--------------|-----------------|
| Tool calls | 63 | 50 |
| Skills invoked | 2 (MVC + JPA) | 1 (MVC) |
| Duration | 22 min | 14 min |
| Coverage | 94.3% | 94.6% |

**v7 invoked spring-mvc-testing purely from L1 description matching** — no router, no prompt steering. The doubt-creating description worked.

---

## Phase 3: N=3 Validation (simple vs hardened+skills)

### Setup

- Router deleted, doubt-creating descriptions active
- Prompt steering removed from v1-hardened.txt
- 3 runs each: simple (v0-simple.txt) and hardened+skills (v1-hardened.txt)
- All 6 runs start from identical 56% template (commit `93e4e1b`)

### Behavioral fingerprint

| Metric | Simple (n=3 avg) | Hardened+Skills (n=3 avg) |
|--------|-----------------|--------------------------|
| Tool calls | 44.3 | 54.0 |
| Skills invoked | 1.0 | 2.3 |
| Duration (min) | 12.1 | 12.1 |
| Coverage | 90-91% | 90-91% |

**All 6 runs invoked spring-mvc-testing** — even the simple 2-line prompt. Doubt descriptions trigger L1 universally.

**Hardened loads more skills but inconsistently**: n1=MVC+JPA, n2=MVC+fundamentals, n3=all three. Non-deterministic skill selection is itself a finding.

### T2 Judge results (original 3-criteria judge)

| Metric | Simple avg | Hardened+Skills avg | Delta |
|--------|-----------|-------------------|-------|
| Slice Selection | 0.98 | 0.93 | -0.05 |
| Assertion Quality | 0.68 | 0.68 | 0.00 |
| Error/Edge Coverage | 0.63 | 0.68 | +0.05 |
| **Weighted Average** | **0.77** | **0.77** | **0.00** |

**No quality difference between variants.** The judge was only scoring 3 of 6 criteria, missing the two that would differentiate: domain-specific patterns and version-aware patterns.

---

## Phase 4: Fix the Judge

The original judge prompt defined 6 criteria but the model output only 3 in prose format, ignoring the JSON schema. Three fixes applied:

### Fix 1: Enforce output structure
Added explicit instruction: "You MUST score ALL 6 criteria. Output exactly this JSON structure with ALL 6 entries."

### Fix 2: Make version-aware patterns checkable
Added mandatory grep instructions:
```
- grep for "MockMvcTester" across all test files — report count
- grep for "mockMvc.perform" across all test files — report count
- If Boot 4+ and ALL tests use MockMvc.perform(): score 0.3 max
```

### Fix 3: Make domain-specific patterns checkable
Added mandatory grep instructions:
```
- grep for "flush()" and "clear()" in @DataJpaTest files — report count
- If save() then findById() WITHOUT flush()+clear(): score 0.3 max
```

### Re-judged results (6-criteria judge, simple-n1 vs hardened-n1)

| Criterion | simple-n1 | hardened-n1 | Delta |
|-----------|-----------|-------------|-------|
| 1. Slice Selection | 1.0 | 1.0 | 0.0 |
| 2. Assertion Quality | 0.8 | 0.8 | 0.0 |
| 3. Error/Edge Coverage | 0.8 | 0.8 | 0.0 |
| 4. Domain-Specific Patterns | **0.3** | **0.3** | 0.0 |
| 5. Coverage Target Selection | 0.8 | 0.8 | 0.0 |
| 6. Version-Aware Patterns | **0.3** | **0.3** | 0.0 |
| **Average** | **0.67** | **0.67** | **0.0** |

**Identical scores.** Both variants fail on criteria 4 and 6 because the agent copies the seed test patterns — `MockMvc.perform()` and no flush/clear — regardless of what skills say.

---

## Phase 5: The Exemplar Effect

### Hypothesis

The agent mimics existing test files (the "exemplars") over skill guidance. If we upgrade the exemplars to use MockMvcTester, the agent should follow.

### Procedure

1. Manually rewrote `OwnerControllerTests.java` and `VetControllerTests.java` to use MockMvcTester (commit `83d69a7`)
   - `@Autowired MockMvc mockMvc` → `@Autowired MockMvcTester mvc`
   - `mockMvc.perform(get(...))` → `mvc.get().uri(...)`
   - Hamcrest matchers → AssertJ assertions
   - `throws Exception` removed from all test methods
   - `when()` → `given()` (BDDMockito consistency)
2. Verified: `./mvnw test` — 27 tests pass
3. Cloned updated template → `hardened-skills-mvctester-n1`
4. Ran hardened+skills variant (same prompt, same skills)
5. Judged with 6-criteria judge

**The only variable that changed: the code in the two seed controller test files.**

### Results

| Criterion | Before (MockMvc exemplar) | After (MockMvcTester exemplar) | Delta |
|-----------|--------------------------|-------------------------------|-------|
| 1. Slice Selection | 1.0 | 1.0 | 0.0 |
| 2. Assertion Quality | 0.8 | **1.0** | **+0.2** |
| 3. Error/Edge Coverage | 0.8 | 0.8 | 0.0 |
| 4. Domain-Specific Patterns | 0.3 | 0.3 | 0.0 |
| 5. Coverage Target Selection | 0.8 | 0.8 | 0.0 |
| 6. Version-Aware Patterns | **0.3** | **1.0** | **+0.7** |
| **Average** | **0.67** | **0.82** | **+0.15** |

### Verification

```
MockMvcTester occurrences: 10 (across 5 controller test files — all of them)
mockMvc.perform() occurrences: 0
```

The agent wrote MockMvcTester in every new controller test file. Zero legacy MockMvc usage anywhere.

### Why criterion 2 also improved

MockMvcTester's API encourages richer assertions. The agent wrote:
- `extractingBindingResult("pet").hasFieldErrors("birthDate")` — specific field error checking
- `bodyJson().extractingPath("$.vetList[0].id").isEqualTo(1)` — structured JSON validation
- Error codes in PetValidatorTests: `getCode()).isEqualTo("required")` — not just existence checks

The Boot 4 API is better designed. When the exemplar uses it, the agent follows, and the assertions improve as a side effect.

### Why criterion 4 stayed at 0.3

`ClinicServiceTests` (the JPA exemplar) still uses `save()` then `findById()` without `flush()/clear()`. We didn't upgrade that exemplar. The agent copied the flaw. This confirms: **fix the exemplar, fix the output. Leave the exemplar broken, the skill is powerless.**

---

## The Partial Knowledge Paradox

The central finding of this experiment:

> **Existing code in the project overrides skill guidance.** The agent trusts what it sees over what it's told.

This is the "partial knowledge paradox" — when the project already has tests, those tests become a ceiling on quality because the agent mimics them. Skills can expand what the agent knows, but they can't override what the agent sees.

### Evidence table

| What changed | Criterion 6 | Criterion 4 |
|-------------|-------------|-------------|
| Skills say "use MockMvcTester" + exemplar uses MockMvc | **0.3** | — |
| Skills say "use MockMvcTester" + exemplar uses MockMvcTester | **1.0** | — |
| Skills say "use flush/clear" + exemplar lacks flush/clear | — | **0.3** |
| Skills say "use flush/clear" + exemplar uses flush/clear | — | **not tested yet** |

The pattern is consistent: skill content is necessary (the agent reads it) but not sufficient (the agent follows the exemplar instead).

### Workshop implication

This reframes the value proposition of skills:

- **Wrong framing**: "Skills teach the agent new patterns"
- **Right framing**: "Skills work when the exemplar is aligned; exemplars work without skills"

The highest-leverage intervention is fixing the seed code, not writing better skills. Skills are documentation for the agent. Exemplars are the code the agent actually copies.

---

## Summary of All Findings

| # | Finding | Evidence |
|---|---------|----------|
| 1 | Router architecture fails — agent stops at L2 hop | v3/v4: 1 skill read, 0 domain skills consumed |
| 2 | Doubt-creating descriptions trigger L1 universally | All 6 N=3 runs invoked skills, including 2-line prompt |
| 3 | Prompt steering is redundant once descriptions work | v7 matched v6 with no steering instructions |
| 4 | PetClinic coverage converges at 90-91% (model floor) | 6 runs, 2 variants, identical coverage |
| 5 | T2 quality identical between simple and hardened+skills | 0.77 vs 0.77 (3-criteria), 0.67 vs 0.67 (6-criteria) |
| 6 | Agent copies exemplar patterns over skill guidance | MockMvc exemplar → MockMvc output; MockMvcTester exemplar → MockMvcTester output |
| 7 | Fixing the exemplar raises quality more than any other lever | Criterion 6: 0.3 → 1.0, Criterion 2: 0.8 → 1.0 |
| 8 | Non-deterministic skill selection across runs | Hardened n1=MVC+JPA, n2=MVC+fundamentals, n3=all three |
| 9 | The T2 judge must grep for specific patterns to catch quality gaps | Original judge missed MockMvcTester and flush/clear entirely |

---

## Workshop Narrative Arc

**Beat 1** — Run the simple variant. Agent writes tests. Coverage reaches 90%. Looks fine.

**Beat 2** — Run the hardened+skills variant. Same coverage. Judge says same quality. "Wait, what are the skills doing?"

**Beat 3** — Look at criterion 6. Both score 0.3. Agent read the skill, ignored it, followed the exemplar. "The agent trusts what it sees over what it's told."

**Beat 4** — Fix the exemplar. Re-run. Criterion 6 jumps to 1.0. "One file change moved the needle more than the entire skills library."

**Beat 5** — The takeaway: before you write skills, fix your seed code. The highest-leverage intervention is the simplest one.

---

## Appendix: Run Directory Map

| Directory | Variant | Template | Key Result |
|-----------|---------|----------|------------|
| `simple-001` | v0-simple | full petclinic (90%) | Early calibration |
| `hardened-001` | v1-hardened | full petclinic (90%) | Early calibration |
| `hardened-skills-001` through `005` | v2-v5 | full petclinic (90%) | Router debugging |
| `hardened-skills-006` | template | partial (56%) | Base template, later upgraded to MockMvcTester |
| `hardened-skills-007` | v1-hardened+skills | partial (56%) | Template validation |
| `simple-n1`, `n2`, `n3` | v0-simple | partial (56%) | N=3 simple variant |
| `hardened-skills-n1`, `n2`, `n3` | v1-hardened+skills | partial (56%) | N=3 hardened+skills |
| `hardened-skills-mvctester-n1` | v1-hardened+skills | partial + MockMvcTester (56%) | Exemplar effect validation |

## Appendix: File Inventory

| File | Purpose |
|------|---------|
| `prompts/v0-simple.txt` | 2-line simple prompt |
| `prompts/v1-hardened.txt` | 7-step structured prompt (steering removed) |
| `prompts/judge-practice-adherence.txt` | 6-criteria T2 judge prompt with grep enforcement |
| `scripts/run-judge.sh` | Judge runner (pipes prompt via stdin, avoids shell expansion) |
| `analyze-trace.py` | JSONL trace analyzer |
