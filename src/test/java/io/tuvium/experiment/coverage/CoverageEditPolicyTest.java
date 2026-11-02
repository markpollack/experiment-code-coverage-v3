package io.tuvium.experiment.coverage;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class CoverageEditPolicyTest {
    @TempDir Path temp;

    @Test void decisionPrecedesAnyEditAndRefusesEscapeOrUnselectedTool() throws Exception {
        Path ws = Files.createDirectory(temp.resolve("workspace"));
        var policy = new CoverageEditPolicy(ws);
        assertThat(policy.beforeTool("Write", Map.of("file_path", "src/test/java/NewTest.java")).allowed()).isTrue();
        for (String path : List.of("../outside", "src/main/Production.java", "pom.xml", "/tmp/outside")) {
            var decision = policy.beforeTool("Write", Map.of("file_path", path));
            if (decision.allowed()) Files.writeString(ws.resolve(path), "must never happen");
            assertThat(decision.allowed()).isFalse();
        }
        assertThat(policy.beforeTool("Read", Map.of("file_path", "src/main/Production.java")).allowed()).isTrue();
        assertThat(policy.beforeTool("Task", Map.of()).allowed()).isFalse();
        assertThat(policy.beforeTool("Write", Map.of()).allowed()).isFalse();
        assertThat(ws.resolve("pom.xml")).doesNotExist();
    }

    @Test void symbolicTestPathsAndComposedOrBackgroundCommandsAreRefused() throws Exception {
        Path ws = Files.createDirectory(temp.resolve("workspace"));
        Files.createDirectories(ws.resolve("src/test"));
        Files.createSymbolicLink(ws.resolve("src/test/link"), temp);
        var policy = new CoverageEditPolicy(ws);
        assertThat(policy.beforeTool("Edit", Map.of("file_path", "src/test/link/outside.java")).allowed()).isFalse();
        assertThat(policy.beforeTool("Bash", Map.of("command", CoverageEditPolicy.BUILD_COMMAND)).allowed()).isTrue();
        assertThat(policy.beforeTool("Bash", Map.of("command", CoverageEditPolicy.BUILD_COMMAND + "; touch pom.xml")).allowed()).isFalse();
        assertThat(policy.beforeTool("Bash", Map.of("command", CoverageEditPolicy.BUILD_COMMAND, "run_in_background", true)).allowed()).isFalse();
    }

    @Test void globPatternsCannotOverrideTheConfinedSearchRoot() throws Exception {
        var policy = new CoverageEditPolicy(temp);
        for (String pattern : List.of("/outside/*", "../*", "src/../../*", "{../*,src/*}", "src/../outside/*"))
            assertThat(policy.beforeTool("Glob", Map.of("pattern", pattern)).allowed()).as(pattern).isFalse();
        for (String pattern : List.of("**/*.java", "src/test/**/*Test.java", "*Test.java"))
            assertThat(policy.beforeTool("Glob", Map.of("pattern", pattern)).allowed()).as(pattern).isTrue();
        assertThat(policy.beforeTool("Glob", Map.of()).allowed()).isFalse();
        Path outside = Files.createTempDirectory("v3-policy-outside-");
        try {
            Files.createSymbolicLink(temp.resolve("linked"), outside);
            assertThat(policy.beforeTool("Glob", Map.of("pattern", "linked/*")).allowed()).isFalse();
        } finally { Files.delete(outside); }
    }

    @Test void postRunIntegrityDetectsProtectedAddsDeletesAndModifications() throws Exception {
        Files.writeString(temp.resolve("pom.xml"), "original");
        Files.writeString(temp.resolve("mvnw"), "original");
        var policy = new CoverageEditPolicy(temp);
        var before = policy.snapshot();
        Files.createDirectories(temp.resolve("src/test/java"));
        Files.writeString(temp.resolve("src/test/java/Test.java"), "allowed test");
        Files.createDirectories(temp.resolve("target"));
        Files.writeString(temp.resolve("target/generated"), "build output");
        assertThat(policy.violations(before)).isEmpty();
        Files.writeString(temp.resolve("pom.xml"), "changed");
        Files.delete(temp.resolve("mvnw"));
        Files.writeString(temp.resolve("new-production-file"), "new");
        assertThat(policy.violations(before)).containsExactly("mvnw", "new-production-file", "pom.xml");
    }
}
