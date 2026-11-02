package io.tuvium.experiment.coverage;

import io.github.markpollack.agents.client.*;
import io.github.markpollack.agents.model.*;
import io.github.markpollack.experiment.runtime.*;
import io.github.markpollack.experiment.runtime.AgentRecords.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class NativeAttemptCustodyTest {
    @TempDir Path root;
    @Test @Timeout(10) void unsettledActualClientStillRetainsElectedDecisionAndUnknownSettlement() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        AgentExecutionProvider provider=options->new AgentExecutionProvider.Connection() {
            public AgentExecutionProvider.Turn prepare(String id) {return new AgentExecutionProvider.Turn() {
                public AgentResponse call(AgentTaskRequest request) {
                    entered.countDown();
                    try {release.await();} catch(InterruptedException e) {Thread.currentThread().interrupt();}
                    return new AgentResponse(List.of(new AgentGeneration("fixture",new AgentGenerationMetadata("SUCCESS",Map.of()))));
                }
                public void stop(boolean interrupt) { }
            };}
            public void close() { }
        };
        try(var client=AgentClient.builder(provider).cleanupTimeout(Duration.ofMillis(100)).build()) {
            var attempt=new Attempt(Identity.of("run","item","simple",0),"invocation","workflow","step","physical",1);
            var started=new AsyncClientInvocationService(client).start(attempt,root,"fixture",Duration.ofSeconds(5));
            try {
                assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
                started.execution().cancel(false);
                Exception failure;
                try {started.execution().awaitTermination(Duration.ofMillis(25));throw new AssertionError("Expected unsettled timeout");}
                catch(TimeoutException expected) {failure=expected;}
                Path receipt=root.resolve("attempt.json");
                NativeAttemptCustody.retain(receipt,attempt,started,null,failure);
                var observed=CoverageEvidence.JSON.readTree(receipt.toFile());
                assertThat(observed.path("decision").path("executionId").asText()).isEqualTo(started.execution().executionId());
                assertThat(observed.path("terminationState").asText()).isEqualTo("NOT_OBSERVED");
                assertThat(observed.path("failure").asText()).contains("TimeoutException");
                assertThat(observed.path("outcomeText").asText()).contains("Cancelled");
                assertThat(observed.path("recording").asText()).isNotBlank();
            } finally {release.countDown();started.execution().awaitTermination(Duration.ofSeconds(2));}
        }
    }
}
