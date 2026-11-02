package io.tuvium.experiment.coverage;

import io.github.markpollack.experiment.runtime.AgentRecords.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class DeterministicCoverageJuryTest {
    @TempDir Path temp;

    @Test void acceptedDeterministicTaskNeverRunsSemanticJudge() throws Exception {
        var policy = fixture(90);
        var result = DeterministicCoverageJury.create(prepared(), measured(90), policy, policy.snapshot(),
                temp.resolve("evidence"), Instant.now().plusSeconds(10)).vote();
        assertThat(result.conclusion().name()).isEqualTo("PASS");
        assertThat(Files.readString(temp.resolve("evidence/judging-mode.json"))).contains("NOT_ASSESSED", "judgeInferenceCalls");
        assertThat(temp.resolve("evidence/t2_quality.json")).doesNotExist();
        assertThat(temp.resolve("evidence/quality")).doesNotExist();
    }

    @Test void protectedPomChangeStopsBeforeBuild() throws Exception {
        var policy = fixture(90); var before = policy.snapshot();
        Files.writeString(temp.resolve("workspace/pom.xml"), "unauthorized");
        var result = DeterministicCoverageJury.create(prepared(), measured(90), policy, before,
                temp.resolve("evidence"), Instant.now().plusSeconds(10)).vote();
        assertThat(result.conclusion().name()).isEqualTo("FAIL");
        assertThat(temp.resolve("workspace/target/site/jacoco/jacoco.xml")).doesNotExist();
    }

    @Test void belowThresholdIsNotAccepted() throws Exception {
        var policy = fixture(80);
        var result = DeterministicCoverageJury.create(prepared(), measured(80), policy, policy.snapshot(),
                temp.resolve("evidence"), Instant.now().plusSeconds(10)).vote();
        assertThat(result.conclusion().name()).isEqualTo("FAIL");
    }

    @Test void independentMeasurementDisagreementIsAnInstrumentError() throws Exception {
        var policy = fixture(90);
        var result = DeterministicCoverageJury.create(prepared(), measured(89), policy, policy.snapshot(),
                temp.resolve("evidence"), Instant.now().plusSeconds(10)).vote();
        assertThat(result.conclusion().name()).isNotEqualTo("PASS");
        assertThat(Files.readString(temp.resolve("evidence/t1_coverage.json"))).contains("differs from independently");
    }

    @Test void expiredJuryAllowanceDoesNotStartBuild() throws Exception {
        var policy = fixture(90);
        var result = DeterministicCoverageJury.create(prepared(), measured(90), policy, policy.snapshot(),
                temp.resolve("evidence"), Instant.now().minusSeconds(1)).vote();
        assertThat(result.conclusion().name()).isNotEqualTo("PASS");
        assertThat(temp.resolve("workspace/target/site/jacoco/jacoco.xml")).doesNotExist();
    }

    @Test void testBuildChangingProtectedFilesCannotPass() throws Exception {
        var policy = fixture(90);
        Files.writeString(temp.resolve("workspace/mvnw"), "echo changed > pom.xml\n", StandardOpenOption.APPEND);
        var result = DeterministicCoverageJury.create(prepared(), measured(90), policy, policy.snapshot(),
                temp.resolve("evidence"), Instant.now().plusSeconds(10)).vote();
        assertThat(result.conclusion().name()).isEqualTo("FAIL");
        assertThat(Files.readString(temp.resolve("evidence/t1_coverage.json"))).contains("changed protected files");
    }

    private CoverageEditPolicy fixture(int covered) throws Exception {
        Files.createDirectory(temp.resolve("workspace"));
        Files.writeString(temp.resolve("workspace/pom.xml"), "<project/>");
        Files.writeString(temp.resolve("workspace/mvnw"), "#!/bin/sh\nmkdir -p target/site/jacoco\n"
                + "echo '<report><counter type=\"LINE\" covered=\"" + covered + "\" missed=\"" + (100-covered)
                + "\"/><counter type=\"BRANCH\" covered=\"10\" missed=\"10\"/></report>' > target/site/jacoco/jacoco.xml\n");
        temp.resolve("workspace/mvnw").toFile().setExecutable(true);
        System.setProperty("coverage.mavenRepo", temp.resolve("m2").toString());
        return new CoverageEditPolicy(temp.resolve("workspace"));
    }
    private PreparedItem prepared() {
        return new PreparedItem(Identity.of("fixture", "petclinic", "simple", 0), temp.resolve("workspace").toString(),
                List.of(Measurement.measured("lineCoverage", "percent", 40, "fixture", "item")), List.of());
    }
    private Domain measured(int coverage) {
        return new Domain(List.of(Measurement.measured("lineCoverage", "percent", coverage, "fixture", "item")), List.of());
    }
}
