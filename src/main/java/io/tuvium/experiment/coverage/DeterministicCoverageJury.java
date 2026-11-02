package io.tuvium.experiment.coverage;

import io.github.markpollack.experiment.runtime.AgentRecords.*;
import io.github.markpollack.judge.Judge;
import io.github.markpollack.judge.coverage.*;
import io.github.markpollack.judge.judgment.*;
import io.github.markpollack.judge.jury.*;
import io.github.markpollack.judge.verdict.RoutingRule;
import io.github.markpollack.judge.voting.ConsensusStrategy;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Deterministic stack-validation jury. It never instantiates a semantic/model judge. */
public final class DeterministicCoverageJury {
    private DeterministicCoverageJury() {}

    public static Jury create(PreparedItem prepared, Domain measured, CoverageEditPolicy policy,
            CoverageEditPolicy.Snapshot before, Path evidence, Instant deadline) throws Exception {
        Path workspace = Path.of(prepared.workspace()).toRealPath();
        if (!workspace.equals(policy.workspace())) throw new IllegalArgumentException("Policy workspace differs from task");
        if (evidence.toAbsolutePath().normalize().startsWith(workspace))
            throw new IllegalArgumentException("Jury evidence must be outside the subject workspace");
        Files.createDirectories(evidence);
        CoverageEvidence.write(evidence.resolve("judging-mode.json"), Map.of(
                "mode", "deterministic-coverage-validation/1", "semanticQuality", "NOT_ASSESSED",
                "judgeInferenceCalls", 0, "minimumLineCoverage", 85, "positiveImprovementRequired", true));
        double baseline = CoverageEvidence.value(prepared.baseline(), "lineCoverage");
        Judge integrity = () -> {
            try {
                var violations = policy.violations(before);
                CoverageEvidence.write(evidence.resolve("edit-surface.json"), Map.of("violations", violations));
                return violations.isEmpty() ? Judgment.pass("Protected files unchanged")
                        : Judgment.fail("Out-of-scope changes: " + violations);
            } catch (Exception failure) { return Judgment.error("Edit integrity unavailable: " + failure); }
        };
        Judge build = () -> {
            try {
                var result = CoverageEvidence.build(workspace, evidence, "independent-build",
                        CoverageEvidence.remaining(deadline, 600_000), "clean", "test", "jacoco:report");
                if (result.timedOut() || !result.drained()) return Judgment.error("Independent build exceeded its allowance");
                return result.passed() ? Judgment.pass("Independent Maven build and tests passed")
                        : Judgment.fail("Independent Maven build/tests failed: exit " + result.exitCode());
            } catch (Exception failure) { return Judgment.error("Independent build unavailable: " + failure); }
        };
        Judge coverage = () -> {
            try {
                var violations = policy.violations(before);
                if (!violations.isEmpty()) return Judgment.fail("Independent build changed protected files: " + violations);
                var fresh = CoverageEvidence.read(workspace.resolve("target/site/jacoco/jacoco.xml"),
                        evidence.resolve("independent-coverage.xml"));
                if (Math.abs(fresh.line() - CoverageEvidence.value(measured.measurements(), "lineCoverage")) > 0.000001)
                    return Judgment.error("Final measurement differs from independently rebuilt report");
                return CoverageImprovementJudge.builder(50.0, 85.0)
                        .evidence(new CoverageComparison(workspace, baseline)).build().judge();
            } catch (Exception failure) { return Judgment.error("Coverage instrument unavailable: " + failure); }
        };
        var prior = new ArrayList<Judgment>();
        var judges = List.of(integrity, build, coverage);
        var names = List.of("edit_surface", "t0_build", "t1_coverage");
        var cascade = CascadedJury.builder();
        for (int i = 0; i < judges.size(); i++) {
            Judge judge = judges.get(i); String name = names.get(i);
            Judge guarded = () -> {
                Judgment judgment;
                if (prior.stream().anyMatch(j -> j.status() == JudgmentStatus.ERROR))
                    judgment = Judgment.error("Earlier instrument error; this check was not executed");
                else try { judgment = judge.judge(); }
                catch (Exception failure) { judgment = Judgment.error("Judge threw: " + failure); }
                prior.add(judgment);
                try { CoverageEvidence.write(evidence.resolve(name + ".json"), judgment); }
                catch (Exception failure) { throw new IllegalStateException("Cannot retain deterministic judgment", failure); }
                return judgment;
            };
            var jury = SimpleJury.builder().judge(name, guarded).votingStrategy(new ConsensusStrategy()).build();
            cascade.tier("validation-" + i, jury,
                    i == judges.size() - 1 ? RoutingRule.FINAL_TIER : RoutingRule.STOP_ON_ANY_OPINION_FAIL);
        }
        return cascade.build();
    }
}
