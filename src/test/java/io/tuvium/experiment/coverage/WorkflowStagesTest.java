package io.tuvium.experiment.coverage;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import io.github.markpollack.experiment.runtime.*;
import io.github.markpollack.experiment.runtime.AgentRecords.*;
import io.github.markpollack.workflow.flows.*;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel;
import io.github.markpollack.workflow.batch.durable.*;
import io.github.markpollack.judge.verdict.Verdict;
import io.github.markpollack.judge.judgment.Judgment;
import io.tuvium.experiment.coverage.CoverageAgentInvoker.*;

/** Deterministic instrument tests; no real agent/domain-success claim. */
class WorkflowStagesTest {
    @TempDir Path temp;
    @Test void actualStagesExecuteAndFailedDomainSurvivesStore() throws Exception {
        Path ws=temp.resolve("workspace"); Files.createDirectory(ws);Files.createDirectory(temp.resolve("attempts"));
        Files.writeString(ws.resolve("pom.xml"),"<project>jacoco-maven-plugin</project>");
        Files.writeString(ws.resolve("mvnw"),"""
                #!/bin/sh
                mkdir -p target/site/jacoco
                echo '<report><counter type="LINE" covered="50" missed="50"/><counter type="BRANCH" covered="10" missed="10"/></report>' > target/site/jacoco/jacoco.xml
                echo deterministic build fixture
                """);
        ws.resolve("mvnw").toFile().setExecutable(true);
        System.setProperty("coverage.mavenRepo",temp.resolve("m2").toString());
        var identity=Identity.of("fixture","petclinic","simple",0);
        var service=new ClaudeInvocationService((req,attempt,dir,budget)->new ClaudeInvocationService.ProviderCall(){
            public ClaudeInvocationService.Delivery invoke(){return new ClaudeInvocationService.Delivery("FAILED","fixture failure",List.of(),false,List.of(),List.of(Measurement.unknown("cost","USD","fixture","item")),"");}
            public void close(){}
        },Duration.ofMillis(100),AcquisitionAssembly.journal());
        var prepare=new Prepare();var invoke=new Invoke(service);var measure=new Measure();
        var graph=Workflows.define("fixture-coverage").maxDuration(Duration.ofMinutes(7)).then("prepare",prepare).then("invoke",invoke).then("measure",measure).terminate(WorkflowModel.Terminal.SUCCEEDED).build();
        var registry=StepRegistry.builder().register("prepare",prepare).register("invoke",invoke).register("measure",measure).build();
        var task=new Task(identity,ws.toString(),"fixture prompt","fixture model",temp.toString());
        var execution=new WorkflowAdapter().execute(temp.resolve("workflow"),graph,registry,new ExecutionCompatibility("fixture","v1",Map.of()),identity,task,Duration.ofSeconds(1),service::terminateActive);
        assertThat(execution.accepted()).isTrue();
        var measured=(Measured)execution.value();
        assertThat(measured.invoked().outcome().invocation().responseState()).isEqualTo("FAILED");
        assertThat(measured.invoked().outcome().invocation().captureState()).isEqualTo("MISSING");
        assertThat(Files.readString(temp.resolve("expanded-prompt.txt"))).contains("Line coverage: 50.0%");
        var row=WorkflowAdapter.assess(execution,measured.invoked().prepared().item(),List.of(measured.invoked().outcome().invocation()),measured.domain(),(p,d)->()->Verdict.single("fixture",Judgment.fail("fixture no improvement")));
        var result=new Result(3,"AGENT_CANONICAL",identity.runId(),"fixture",1,List.of(row),List.of());
        var store=new FileSystemResultStore(temp.resolve("results"));store.save(result);
        assertThat(new FileSystemResultStore(temp.resolve("results")).load(identity.runId())).contains(result);
        assertThat(row.verdicts().getFirst().conclusion()).isEqualTo("FAIL");Evidence.verify(row);
        var other=new PreparedItem(Identity.of("wrong-run","petclinic","simple",0),ws.toString(),List.of(),List.of());
        assertThatThrownBy(()->WorkflowAdapter.assess(execution,other,row.invocations(),measured.domain(),(p,d)->()->Verdict.single("fixture",Judgment.pass("unused")))).hasMessageContaining("row mismatch");
    }
}
