package io.tuvium.experiment.coverage;

import io.github.markpollack.agents.client.*;
import io.github.markpollack.agents.model.*;
import io.github.markpollack.experiment.runtime.*;
import io.github.markpollack.experiment.runtime.AgentRecords.*;
import io.github.markpollack.workflow.flows.*;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel;
import io.github.markpollack.workflow.batch.durable.*;
import io.tuvium.experiment.coverage.CoverageAgentInvoker.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Timeout(15)
class NativeResponseCustodyTest {
    @TempDir Path root;
    private static final String TEXT="  actual Client projection\nsecond line \u03bb  ";

    @Test void verifierFailureRetainsActualResponseWithoutSuccessfulWorkflowOrInvocation() throws Exception {
        exercise(false,false);
        var observed=CoverageEvidence.JSON.readTree(root.resolve("evidence/observed-client-response.json").toFile());
        var custody=CoverageEvidence.JSON.readTree(root.resolve("evidence/native-attempt.json").toFile());
        assertThat(observed.path("observation").asText()).isEqualTo("RETURNED");
        assertThat(observed.path("resultText").asText()).isEqualTo(TEXT);
        assertThat(observed.path("successful").asBoolean()).isTrue();
        assertThat(observed.path("decisionId")).isEqualTo(custody.path("decision").path("decisionId"));
        assertThat(observed.path("attempt")).isEqualTo(custody.path("attempt"));
        assertThat(custody.path("observedResponse")).isEqualTo(observed);
        assertThat(custody.path("failure").asText()).contains("deliberate downstream verifier rejection");
        byte[] retained=Files.readAllBytes(root.resolve("evidence/observed-client-response.json"));
        var value=CoverageEvidence.JSON.readValue(retained,NativeAttemptCustody.ObservedResponse.class);
        NativeAttemptCustody.retainResponse(root.resolve("evidence/observed-client-response.json"),value);
        assertThat(Files.readAllBytes(root.resolve("evidence/observed-client-response.json"))).isEqualTo(retained);
    }

    @Test void thrownResultRetainsNoInventedResponse() throws Exception {
        exercise(true,false);
        var observed=CoverageEvidence.JSON.readTree(root.resolve("evidence/observed-client-response.json").toFile());
        assertThat(observed.path("observation").asText()).isEqualTo("THREW");
        assertThat(observed.path("resultText").isNull()).isTrue();
        assertThat(observed.path("successful").isNull()).isTrue();
        assertThat(observed.path("error").asText()).isNotBlank();
    }

    @Test void conflictingCustodyDoesNotSkipCleanupOrOverwritePriorBytes() throws Exception {
        exercise(false,true);
        assertThat(Files.readString(root.resolve("evidence/observed-client-response.json"))).isEqualTo("prior immutable bytes");
        var custody=CoverageEvidence.JSON.readTree(root.resolve("evidence/native-attempt.json").toFile());
        assertThat(custody.path("failure").asText()).contains("Conflicting observed response custody");
        assertThat(custody.path("responseCustodyError").asText()).contains("Conflicting observed response custody");
        assertThat(custody.path("observedResponse").path("resultText").asText()).isEqualTo(TEXT);
    }

    private void exercise(boolean resultThrows, boolean conflictingFile) throws Exception {
        Path workspace=Files.createDirectory(root.resolve("workspace"));
        Path evidence=Files.createDirectory(root.resolve("evidence"));
        Path ledger=root.resolve("ledger.json");
        Files.writeString(ledger,"{\"kind\":\"v3-native-coverage-attempts/1\",\"limit\":6,\"attempts\":[]}");
        if(conflictingFile) Files.writeString(evidence.resolve("observed-client-response.json"),"prior immutable bytes");
        var calls=new AtomicInteger();var closed=new AtomicBoolean();var verifierCalls=new AtomicInteger();
        AgentExecutionProvider provider=options->new AgentExecutionProvider.Connection() {
            public AgentExecutionProvider.Turn prepare(String id) {return new AgentExecutionProvider.Turn() {
                public AgentResponse call(AgentTaskRequest request) {
                    calls.incrementAndGet();
                    if(resultThrows) throw new IllegalStateException("deliberate provider failure");
                    return new AgentResponse(List.of(new AgentGeneration(TEXT,new AgentGenerationMetadata("SUCCESS",Map.of()))));
                }
                public void stop(boolean interrupt) { }
            };}
            public void close() {closed.set(true);}
        };
        ExecutionRecorder recorder=decision->()->{
            try {
                Path candidate=Files.createDirectories(evidence.resolve("journal/prepared/candidate"));
                Path events=candidate.resolve("events.jsonl");Files.writeString(events,"fixture only\n");
                CoverageEvidence.write(candidate.resolve("marker.json"),Map.of("ack",Map.of("bindingId","actual-fixture-binding",
                        "executionId",decision.executionId(),"decisionId",decision.decisionId(),
                        "artifacts",List.of(Map.of("relativePath","prepared/candidate/events.jsonl",
                                "sha256",Evidence.sha256(events),"size",Files.size(events))))));
                return "actual-fixture-binding";
            } catch(IOException error) {throw new UncheckedIOException(error);}
        };
        var identity=Identity.of("custody-fixture","item","simple",0);
        var task=new Task(identity,workspace.toString(),"fixture", "fixture",evidence.toString());
        var prepared=new Prepared(task,new PreparedItem(identity,workspace.toString(),List.of(),List.of()),"fixture");
        var active=new AtomicReference<AgentExecution>();
        var invoke=new NativeCoverageApp.Invoke(active,evidence,ledger,Instant.now().plusSeconds(900),new CoverageEditPolicy(workspace).snapshot()) {
            @Override Invocation accept(AsyncClientInvocationService.StartedExecution started,ExecutionTermination termination,
                    Path events,long budget,long elapsed) throws Exception {
                verifierCalls.incrementAndGet();
                assertThat(termination.cleanupConfirmed()).isTrue();
                assertThat(CoverageEvidence.JSON.readTree(evidence.resolve("observed-client-response.json").toFile())
                        .path("resultText").asText()).isEqualTo(TEXT);
                throw new IOException("deliberate downstream verifier rejection");
            }
        };
        try(var client=AgentClient.builder(provider).executionRecorder(recorder).cleanupTimeout(Duration.ofSeconds(1)).build()) {
            invoke.service=new AsyncClientInvocationService(client);
            var graph=Workflows.define("response-custody-fixture").maxDuration(Duration.ofMinutes(7))
                    .then("invoke",invoke).terminate(WorkflowModel.Terminal.SUCCEEDED).build();
            var registry=StepRegistry.builder().register("invoke",invoke).build();
            var execution=new WorkflowAdapter().execute(root.resolve("workflow"),graph,registry,
                    new ExecutionCompatibility("response-custody-fixture","v1",Map.of()),identity,prepared,
                    Duration.ofSeconds(1),()->{if(active.get()!=null) active.get().cancel(false);});
            assertThat(execution.accepted()).isFalse();
        }
        assertThat(calls.get()).isEqualTo(1);assertThat(closed.get()).isTrue();
        assertThat(verifierCalls.get()).isEqualTo(resultThrows||conflictingFile?0:1);
        assertThat(Files.exists(evidence.resolve("invoked.json"))).isFalse();
        var custody=CoverageEvidence.JSON.readTree(evidence.resolve("native-attempt.json").toFile());
        assertThat(custody.path("termination").path("cleanupConfirmed").asBoolean()).isTrue();
        assertThat(CoverageEvidence.JSON.readTree(ledger.toFile()).path("attempts")).hasSize(1);
    }
}
