package io.tuvium.experiment.coverage;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import io.github.markpollack.experiment.runtime.AgentRecords.*;

class JuryFactoryTest {
    @TempDir Path temp;
    @Test void missingReportCannotBeHiddenByFinalTier() throws Exception {
        Files.writeString(temp.resolve("pom.xml"),"<project/>");
        Path wrapper=temp.resolve("mvnw");Files.writeString(wrapper,"#!/bin/sh\nexit 0\n");wrapper.toFile().setExecutable(true);
        System.setProperty("coverage.mavenRepo",temp.resolve("m2").toString());
        var prepared=new PreparedItem(Identity.of("fixture","petclinic","simple",0),temp.toString(),List.of(Measurement.measured("lineCoverage","percent",40,"fixture","item")),List.of());
        var domain=new Domain(List.of(Measurement.measured("lineCoverage","percent",90,"fixture","item")),List.of());
        Path evidence=temp.resolve("judges");
        var verdict=JuryFactory.create(prepared,domain,temp.resolve("nonexistent-prompt"),evidence).vote();
        assertThat(verdict.conclusion().name()).isNotEqualTo("PASS");
        assertThat(Files.readString(evidence.resolve("t1_coverage.json"))).contains("error","Missing fresh JaCoCo");
        assertThat(Files.readString(evidence.resolve("t2_quality.json"))).contains("Earlier instrument error");
        assertThat(evidence.resolve("quality")).doesNotExist();
    }
}
