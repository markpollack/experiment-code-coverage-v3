package io.tuvium.experiment.coverage;

import io.github.markpollack.agents.client.*;
import io.github.markpollack.agents.claude.ClaudeAcquisition;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Actual public Client+SDK subprocess wiring, never native inference or source qualification. */
@Timeout(30)
class NativeCoverageClientTest {
    @TempDir Path root;
    /** Client's package-private one-shot capture limit; the fixture's overflow mode writes past it. */
    static final long OVERFLOW_LIMIT_BYTES = 256L * 1024 * 1024;
    static final long OVERFLOW_STDERR_BYTES = OVERFLOW_LIMIT_BYTES + 64 * 1024;

    @Test void configuredModelCapAndRequiredAllowDenyHookReachSubprocess() throws Exception {
        var fixture = fixture();
        try (var client = NativeCoverageClient.configured(fixture.workspace(), fixture.evidence(), fixture.executable(), d -> () -> "fixture")) {
            var execution = client.goal("hooks").runAsync();
            assertThat(execution.result().getResult()).isEqualTo("fixture-complete");
            assertThat(execution.awaitTermination(Duration.ofSeconds(10)).cleanupConfirmed()).isTrue();
        }
        var argv = CoverageEvidence.JSON.readTree(fixture.workspace().resolve("fixture-argv.json").toFile());
        List<String> args = new ArrayList<>(); argv.forEach(value -> args.add(value.asText()));
        assertThat(args.get(args.indexOf("--model") + 1)).isEqualTo(NativeCoverageClient.MODEL);
        assertThat(args.get(args.indexOf("--max-budget-usd") + 1)).isEqualTo("5.0");
        assertThat(Files.readAllLines(fixture.workspace().resolve("fixture-policy.jsonl"))).hasSize(3);
        String decisions = Files.readString(fixture.evidence().resolve("tool-policy.jsonl"));
        assertThat(decisions).contains("\"allowed\":true", "\"allowed\":false", "Glob");
        var inherited = CoverageEvidence.JSON.readTree(fixture.workspace().resolve("fixture-maven-env.json").toFile());
        assertThat(inherited.isNull() ? null : inherited.asText()).isEqualTo(System.getenv("MAVEN_ARGS"));
        assertDead(fixture);
    }

    @Test void actualExecutionTimeoutStopsTheOwnedSubprocess() throws Exception {
        var fixture = fixture();
        long start = System.nanoTime();
        try (var client = NativeCoverageClient.configured(fixture.workspace(), fixture.evidence(), fixture.executable(), d -> () -> "fixture")) {
            var execution = client.goal("wait").executionTimeout(Duration.ofMillis(300)).runAsync();
            assertThatThrownBy(execution::result).isInstanceOf(AgentExecutionException.class);
            assertThat(execution.awaitTermination(Duration.ofSeconds(15)).cleanupConfirmed()).isTrue();
        }
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(18));
        assertDead(fixture);
    }

    @Test void nativeBudgetExhaustionIsNotSuccess() throws Exception {
        var fixture = fixture();
        try (var client = NativeCoverageClient.configured(fixture.workspace(), fixture.evidence(), fixture.executable(), d -> () -> "fixture")) {
            var execution = client.goal("budget-exhausted").runAsync();
            assertThat(execution.result().isSuccessful()).isFalse();
            assertThat(execution.awaitTermination(Duration.ofSeconds(10)).cleanupConfirmed()).isTrue();
        }
        assertDead(fixture);
    }

    @Test void sourceBufferOverflowRemainsIncomplete() throws Exception {
        var fixture = fixture();
        var source = new CompletableFuture<ClaudeAcquisition>();
        ExecutionRecorder recorder = new ExecutionRecorder() {
            public Supplier<String> prepare(ExecutionDecision decision) { throw new AssertionError(); }
            public Supplier<String> prepare(ExecutionDecision decision, Map<String, Object> evidence) {
                source.complete((ClaudeAcquisition) evidence.get("claudeAcquisition"));
                return () -> "fixture";
            }
        };
        try (var client = NativeCoverageClient.configured(fixture.workspace(), fixture.evidence(), fixture.executable(), recorder)) {
            var execution = client.goal("overflow").runAsync();
            assertThat(execution.result().isSuccessful()).isTrue();
            var acquired = source.get(10, TimeUnit.SECONDS);
            assertThat(acquired.complete()).isFalse();
            // Client 84c17d9f: one 256 MiB limit across all streams, named with the dropped count.
            var named = java.util.regex.Pattern.compile("Capture limit of (\\d+) bytes reached; dropped (\\d+) bytes")
                    .matcher(Files.readString(acquired.manifest()));
            assertThat(named.find()).as("capture names the limit and the dropped bytes").isTrue();
            long limit = Long.parseLong(named.group(1)), dropped = Long.parseLong(named.group(2));
            assertThat(limit).isEqualTo(OVERFLOW_LIMIT_BYTES);
            assertThat(OVERFLOW_STDERR_BYTES).isGreaterThan(limit);
            // At most `limit` bytes are held, so at least the stderr excess is dropped (stdout adds to the total).
            assertThat(dropped).isGreaterThanOrEqualTo(OVERFLOW_STDERR_BYTES - limit);
        }
        assertDead(fixture);
    }

    @Test void taskLocalMavenEnvironmentMustMatchExactly() throws Exception {
        Path repo = Files.createDirectory(root.resolve("m2")).toRealPath();
        assertThat(NativeCoverageClient.requireMavenEnvironment(repo.toString(), "-Dmaven.repo.local=" + repo)).isEqualTo(repo);
        assertThatThrownBy(() -> NativeCoverageClient.requireMavenEnvironment(repo.toString(), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NativeCoverageClient.requireMavenEnvironment(repo.toString(), "-Dmaven.repo.local=/other")).isInstanceOf(IllegalArgumentException.class);
    }

    private record Fixture(Path workspace, Path evidence, String executable) {}
    private Fixture fixture() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        Path evidence = Files.createDirectory(root.resolve("evidence"));
        Path child = root.resolve("native-coverage-child.py");
        try (var stream = getClass().getResourceAsStream("/native-coverage-child.py")) { Files.copy(stream, child); }
        child.toFile().setExecutable(true);
        return new Fixture(workspace, evidence, child.toString());
    }
    private void assertDead(Fixture fixture) throws Exception {
        Path pid = fixture.workspace().resolve("fixture-pid");
        if (Files.exists(pid)) assertThat(ProcessHandle.of(Long.parseLong(Files.readString(pid))).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }
}
