package io.tuvium.experiment.coverage;

import io.github.markpollack.agents.client.*;
import io.github.markpollack.experiment.runtime.*;
import io.github.markpollack.experiment.runtime.AgentRecords.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Timeout(30)
class CodexCoverageClientTest {
    @TempDir Path root;
    static final String FOREIGN_REFUSAL="Native binding mismatch: acquisition_uid";
    @Test void packagedLocalChildPreservesOriginalDecisionAndAdmitsQualifiedArchive() throws Exception {
        var fixture=fixture();
        try(var client=CodexCoverageClient.openFixture(fixture.workspace(),fixture.evidence(),fixture.child().toString(),"success")) {
            // Started directly through Client, not by a Workflow step: a direct attempt (schema 5).
            var attempt=Attempt.direct(Identity.of("codex-fixture","item","simple",0),"invocation",1);
            var started=new AsyncClientInvocationService(client).start(attempt,fixture.workspace(),"exact pinned prompt",Duration.ofSeconds(10));
            var response=started.execution().result();var decision=started.decision().orElseThrow();
            var observed=NativeAttemptCustody.returned(started,response);
            NativeAttemptCustody.retainResponse(fixture.evidence().resolve("observed-client-response.json"),observed);
            var termination=started.execution().awaitTermination(Duration.ofSeconds(10));
            await(started.execution());
            assertThat(response.getResult()).isEqualTo("answer:exact pinned prompt");
            assertThat(started.execution().recording().delivery()).as(started.execution().recording().delivery().toString()).isInstanceOf(TerminalDelivery.Completed.class);
            var completed=(TerminalDelivery.Completed)started.execution().recording().delivery();
            var nativeRecording=started.execution().recording().nativeRecording().orElseThrow();
            assertThat(nativeRecording.outcome()).as(nativeRecording.toString()).isEqualTo(NativeRecording.Outcome.ATTACHED);
            assertThat(nativeRecording.acquisitionProfile()).contains("codex-cli-"+CodexCoverageClient.VERSION+"-exec-stdio-rollout");
            Path events=NativeCoverageApp.selectPrepared(fixture.evidence().resolve("journal"),completed.bindingId(),decision.executionId(),decision.decisionId());
            var invocation=NativeClientInvocation.accept(started,termination,CodexCoverageClient.originalManifest(fixture.evidence()),
                    fixture.evidence().resolve("journal/native-"+decision.executionId()),events,10000,1000);
            assertThat(invocation.attempt()).isEqualTo(attempt);assertThat(invocation.responseText()).isEqualTo(response.getResult());
            assertThat(started.decision().orElseThrow()).isSameAs(decision);Evidence.verify(invocation.canonical().getFirst());
            var changed=fixture.evidence().resolve("foreign.json");
            var manifest=CoverageEvidence.JSON.readTree(Path.of(invocation.acquisitions().getFirst().path()).toFile());
            ((com.fasterxml.jackson.databind.node.ObjectNode)manifest).put("acquisition_uid","foreign");CoverageEvidence.write(changed,manifest);
            assertThatThrownBy(()->NativeClientInvocation.accept(started,termination,changed,fixture.evidence().resolve("journal/native-"+decision.executionId()),events,10000,1000))
                    .isInstanceOf(java.io.IOException.class).hasMessageContaining(FOREIGN_REFUSAL);
        }
    }
    @Test void failedResultAndWrongCaptureRetainOriginalOutcomeWithoutAdmission() throws Exception {
        for(String mode:List.of("failed","wrong")) {
            var fixture=fixture();
            try(var client=CodexCoverageClient.openFixture(fixture.workspace(),fixture.evidence(),fixture.child().toString(),mode)) {
                var attempt=Attempt.direct(Identity.of(mode,"item","simple",0),"invocation",1);
                var started=new AsyncClientInvocationService(client).start(attempt,fixture.workspace(),"original prompt",Duration.ofSeconds(10));
                var response=started.execution().result();var decision=started.decision().orElseThrow();
                var termination=started.execution().awaitTermination(Duration.ofSeconds(10));await(started.execution());
                NativeAttemptCustody.retain(fixture.evidence().resolve("native-attempt.json"),attempt,started,termination,NativeAttemptCustody.returned(started,response),null);
                assertThat(termination.cleanupConfirmed()).isTrue();assertThat(started.decision().orElseThrow()).isSameAs(decision);
                assertThat(Files.readString(fixture.evidence().resolve("observed-client-response.json"))).contains(decision.decisionId());
                if(mode.equals("failed")) {
                    assertThat(response.isSuccessful()).isFalse();
                    assertThatThrownBy(()->NativeClientInvocation.accept(started,termination,fixture.evidence().resolve("unused"),fixture.evidence().resolve("unused-q"),fixture.evidence().resolve("unused-events"),10000,1000))
                            .hasMessageContaining("Only elected SUCCESS");
                }
                else {
                    // Client's Codex capture sees a foreign native session and seals an incomplete acquisition.
                    // The recorder still delivers one evidence-only binding and reports REJECTED, so the
                    // delivery completes (before the native-generation path V3 failed the delivery instead).
                    // Admission is refused either way.
                    assertThat(response.isSuccessful()).isTrue();
                    assertThat(started.execution().recording().delivery()).isInstanceOf(TerminalDelivery.Completed.class);
                    var nativeRecording=started.execution().recording().nativeRecording().orElseThrow();
                    assertThat(nativeRecording.outcome()).as(nativeRecording.toString()).isEqualTo(NativeRecording.Outcome.REJECTED);
                    assertThat(nativeRecording.detail()).contains("Native acquisition is incomplete");
                    assertThat(started.execution().recording().evidence().acquisition()).isEqualTo(CaptureAcquisition.INCOMPLETE);
                    var completed=(TerminalDelivery.Completed)started.execution().recording().delivery();
                    Path events=NativeCoverageApp.selectPrepared(fixture.evidence().resolve("journal"),completed.bindingId(),decision.executionId(),decision.decisionId());
                    assertThatThrownBy(()->NativeClientInvocation.accept(started,termination,CodexCoverageClient.originalManifest(fixture.evidence()),
                            fixture.evidence().resolve("journal/native-"+decision.executionId()),events,10000,1000))
                            .hasMessageContaining("Incomplete or unrelated terminal recording");
                }
            }
        }
    }
    @Test void nativeOrSubstitutedExecutableCannotEnterFixtureRoute() throws Exception {
        var fixture=fixture();Files.writeString(fixture.child(),"#!/bin/sh\nexit 0\n");
        assertThatThrownBy(()->CodexCoverageClient.openFixture(fixture.workspace(),fixture.evidence(),fixture.child().toString(),"success")).hasMessageContaining("packaged local child");
        assertThatThrownBy(()->CodexCoverageClient.openNative(fixture.workspace(),fixture.evidence(),"codex")).hasMessageContaining("policy is unqualified");
    }
    record Fixture(Path workspace,Path evidence,Path child) {}
    Fixture fixture() throws Exception {
        Path area=Files.createTempDirectory(root,"case-");Path workspace=Files.createDirectory(area.resolve("workspace"));Path evidence=Files.createDirectory(area.resolve("evidence"));
        for(String name:List.of("codex-child.py","codex-native-template.jsonl")) try(var in=getClass().getResourceAsStream("/"+name)) {Files.copy(in,area.resolve(name));}
        Path child=area.resolve("codex-child.py");child.toFile().setExecutable(true);return new Fixture(workspace,evidence,child);
    }
    static void await(AgentExecution execution) throws Exception {
        long end=System.nanoTime()+Duration.ofSeconds(10).toNanos();
        while(!(execution.recording().delivery() instanceof TerminalDelivery.Completed) && !(execution.recording().delivery() instanceof TerminalDelivery.Failed) && System.nanoTime()<end) Thread.sleep(10);
        assertThat(execution.recording().delivery()).isInstanceOfAny(TerminalDelivery.Completed.class,TerminalDelivery.Failed.class);
    }
}
