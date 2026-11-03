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
    @Test void packagedLocalChildPreservesOriginalDecisionAndAdmitsQualifiedArchive() throws Exception {
        var fixture=fixture();
        try(var client=CodexCoverageClient.openFixture(fixture.workspace(),fixture.evidence(),fixture.child().toString(),"success")) {
            var attempt=new Attempt(Identity.of("codex-fixture","item","simple",0),"invocation","workflow","workflow-invocation","workflow-attempt",1);
            var started=new AsyncClientInvocationService(client).start(attempt,fixture.workspace(),"exact pinned prompt",Duration.ofSeconds(10));
            var response=started.execution().result();var decision=started.decision().orElseThrow();
            var observed=NativeAttemptCustody.returned(started,response);
            NativeAttemptCustody.retainResponse(fixture.evidence().resolve("observed-client-response.json"),observed);
            var termination=started.execution().awaitTermination(Duration.ofSeconds(10));
            await(started.execution());
            assertThat(response.getResult()).isEqualTo("answer:exact pinned prompt");
            assertThat(started.execution().recording().delivery()).as(started.execution().recording().delivery().toString()).isInstanceOf(TerminalDelivery.Completed.class);
            var completed=(TerminalDelivery.Completed)started.execution().recording().delivery();
            Path events=NativeCoverageApp.selectPrepared(fixture.evidence().resolve("journal"),completed.bindingId(),decision.executionId(),decision.decisionId());
            var invocation=NativeClientInvocation.accept(started,termination,CodexCoverageClient.originalManifest(fixture.evidence()),
                    fixture.evidence().resolve("journal/native-"+decision.executionId()),events,10000,1000);
            assertThat(invocation.attempt()).isEqualTo(attempt);assertThat(invocation.responseText()).isEqualTo(response.getResult());
            assertThat(started.decision().orElseThrow()).isSameAs(decision);Evidence.verify(invocation.canonical().getFirst());
            var changed=fixture.evidence().resolve("foreign.json");
            var manifest=CoverageEvidence.JSON.readTree(Path.of(invocation.acquisitions().getFirst().path()).toFile());
            ((com.fasterxml.jackson.databind.node.ObjectNode)manifest).put("acquisition_uid","foreign");CoverageEvidence.write(changed,manifest);
            assertThatThrownBy(()->NativeClientInvocation.accept(started,termination,changed,fixture.evidence().resolve("journal/native-"+decision.executionId()),events,10000,1000)).isInstanceOf(Exception.class);
        }
    }
    @Test void failedResultAndWrongCaptureRetainOriginalOutcomeWithoutAdmission() throws Exception {
        for(String mode:List.of("failed","wrong")) {
            var fixture=fixture();
            try(var client=CodexCoverageClient.openFixture(fixture.workspace(),fixture.evidence(),fixture.child().toString(),mode)) {
                var attempt=new Attempt(Identity.of(mode,"item","simple",0),"invocation","workflow","workflow-invocation","workflow-attempt",1);
                var started=new AsyncClientInvocationService(client).start(attempt,fixture.workspace(),"original prompt",Duration.ofSeconds(10));
                var response=started.execution().result();var decision=started.decision().orElseThrow();
                var termination=started.execution().awaitTermination(Duration.ofSeconds(10));await(started.execution());
                NativeAttemptCustody.retain(fixture.evidence().resolve("native-attempt.json"),attempt,started,termination,NativeAttemptCustody.returned(started,response),null);
                assertThat(termination.cleanupConfirmed()).isTrue();assertThat(started.decision().orElseThrow()).isSameAs(decision);
                assertThat(Files.readString(fixture.evidence().resolve("observed-client-response.json"))).contains(decision.decisionId());
                if(mode.equals("failed")) assertThat(response.isSuccessful()).isFalse();
                else {assertThat(response.isSuccessful()).isTrue();assertThat(started.execution().recording().delivery()).isInstanceOf(TerminalDelivery.Failed.class);}
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
