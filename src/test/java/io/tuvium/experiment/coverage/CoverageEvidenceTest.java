package io.tuvium.experiment.coverage;

import java.nio.file.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class CoverageEvidenceTest {
    @TempDir Path temp;
    @Test void aggregateReportOnlyAndImmutableCopy() throws Exception {
        Path xml=temp.resolve("report.xml");
        Files.writeString(xml,"<report><package><counter type='LINE' covered='1' missed='99'/></package><counter type='LINE' covered='85' missed='15'/><counter type='BRANCH' covered='1' missed='1'/></report>");
        var c=CoverageEvidence.read(xml,temp.resolve("saved.xml")); assertThat(c.line()).isEqualTo(85); assertThat(c.branch()).isEqualTo(50);
        assertThatThrownBy(()->CoverageEvidence.read(xml,temp.resolve("saved.xml"))).isInstanceOf(FileAlreadyExistsException.class);
    }
    @Test void missingMalformedAndEmptyReportsAreNotZero() throws Exception {
        Path xml=temp.resolve("report.xml");
        assertThatThrownBy(()->CoverageEvidence.read(xml,temp.resolve("saved.xml"))).hasMessageContaining("Missing");
        for(String content:new String[]{"bad","<report/>","<report><counter type='LINE' covered='0' missed='0'/></report>","<report><counter type='LINE' covered='-1' missed='1'/></report>"}) {
            Files.writeString(xml,content); assertThatThrownBy(()->CoverageEvidence.read(xml,temp.resolve("saved.xml"))).isInstanceOf(Exception.class);
        }
    }
    @Test void itemDeadlineIncludesFinalMeasurementReserve() {
        long budget=CoverageAgentInvoker.providerBudget(Instant.now().plusSeconds(1800));
        assertThat(budget).isBetween(1_469_000L,1_470_000L);
        assertThatThrownBy(()->CoverageAgentInvoker.providerBudget(Instant.now().plusSeconds(300))).hasMessageContaining("No provider time");
    }
    @Test void failedBuildRetainsNonzeroExit() throws Exception {
        Path wrapper=temp.resolve("mvnw"); Files.writeString(wrapper,"#!/bin/sh\necho fixture build failure\nexit 7\n"); wrapper.toFile().setExecutable(true);
        System.setProperty("coverage.mavenRepo",temp.resolve("m2").toString());
        var result=CoverageEvidence.build(temp,temp.resolve("evidence"),"failed",2000,"test");
        assertThat(result.passed()).isFalse(); assertThat(result.exitCode()).isEqualTo(7);
        assertThat(Files.readString(Path.of(result.log().path()))).contains("fixture build failure");
    }
}
