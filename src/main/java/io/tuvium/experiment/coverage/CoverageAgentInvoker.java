package io.tuvium.experiment.coverage;

import io.github.markpollack.experiment.runtime.*;
import io.github.markpollack.experiment.runtime.AgentRecords.*;
import io.github.markpollack.workflow.flows.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Concrete domain stages. Shared Experiment owns provider invocation and persistence. */
public final class CoverageAgentInvoker {
    public static final long MEASUREMENT_RESERVE_MS=300_000;
    public static final long DRAIN_RESERVE_MS=30_000;
    public record Task(Identity identity,String workspace,String prompt,String model,String evidenceRoot) {}
    public record Prepared(Task task,PreparedItem item,String expandedPrompt) {}
    public record Invoked(Prepared prepared,ClaudeInvocationService.Outcome outcome) {}
    public record Measured(Invoked invoked,Domain domain) {}

    public static final class Prepare implements Step<Task,Prepared> {
        public Prepared execute(StepContext context,Task task) {
            try { return executeChecked(context,task); }
            catch (Exception e) { throw new IllegalStateException("Coverage stage failed",e); }
        }
        private Prepared executeChecked(StepContext context,Task task) throws Exception {
            Path ws=Path.of(task.workspace()), evidence=Path.of(task.evidenceRoot()).resolve("domain");
            var compile=CoverageEvidence.build(ws,evidence,"prepare-compile",CoverageEvidence.remaining(context.deadline(),300_000),"clean","compile");
            if(!compile.passed()) throw new IllegalStateException("Baseline compilation failed; see " + compile.log().path());
            // Snapshot already owns JaCoCo configuration; never alter its coverage scope.
            if(!Files.readString(ws.resolve("pom.xml")).contains("jacoco-maven-plugin")) throw new IllegalStateException("Pinned dataset lost JaCoCo configuration");
            var build=CoverageEvidence.build(ws,evidence,"baseline",CoverageEvidence.remaining(context.deadline(),600_000),"clean","test","jacoco:report");
            if(!build.passed()) throw new IllegalStateException("Baseline tests failed; no measured baseline");
            var baseline=CoverageEvidence.read(ws.resolve("target/site/jacoco/jacoco.xml"),evidence.resolve("baseline.xml"));
            var item=new PreparedItem(task.identity(),task.workspace(),CoverageEvidence.metrics(baseline,task.identity().launchKey()),List.of(compile.log(),build.log(),baseline.report()));
            String prompt=task.prompt()+String.format(Locale.ROOT,"\n\n## Current Coverage Metrics\n- Line coverage: %.1f%%\n- Branch coverage: %.1f%%\n- Lines covered: %d/%d\n\nNote: JaCoCo is already configured. Run `./mvnw clean test jacoco:report` to regenerate.\n",baseline.line(),baseline.branch(),baseline.covered(),baseline.covered()+baseline.missed());
            var prepared=new Prepared(task,item,prompt);
            CoverageEvidence.write(Path.of(task.evidenceRoot()).resolve("prepared.json"),prepared);
            Files.writeString(Path.of(task.evidenceRoot()).resolve("expanded-prompt.txt"),prompt,StandardOpenOption.CREATE_NEW);
            return prepared;
        }
    }
    public static long providerBudget(Instant deadline) {
        long budget=Duration.between(Instant.now(),deadline).toMillis()-MEASUREMENT_RESERVE_MS-DRAIN_RESERVE_MS;
        if(budget<=0) throw new IllegalStateException("No provider time after final measurement and drain reserve");
        return budget;
    }
    public static final class Invoke implements Step<Prepared,Invoked> {
        private final ClaudeInvocationService service;
        public Invoke(ClaudeInvocationService service) { this.service=service; }
        public Invoked execute(StepContext context,Prepared prepared) {
            try { return executeChecked(context,prepared); }
            catch (Exception e) { throw new IllegalStateException("Coverage stage failed",e); }
        }
        private Invoked executeChecked(StepContext context,Prepared prepared) throws Exception {
            long budget=providerBudget(context.deadline());
            Path root=Path.of(prepared.task().evidenceRoot());
            CoverageEvidence.write(root.resolve("provider-budget.json"),Map.of("deadline",context.deadline().toString(),"providerBudgetMs",budget,"measurementReserveMs",MEASUREMENT_RESERVE_MS,"drainReserveMs",DRAIN_RESERVE_MS));
            var request=new ClaudeInvocationService.Request(prepared.item(),prepared.expandedPrompt(),prepared.task().model(),budget,root.resolve("attempts").resolve(context.attemptId()).toString(),true);
            var outcome=service.invoke(request,context);
            if(!outcome.workspaceState().equals("RETAINED")) throw new IllegalStateException("Unsettled provider; forbid successor measurement");
            var invoked=new Invoked(prepared,outcome);
            CoverageEvidence.write(root.resolve("invoked.json"),invoked);
            return invoked;
        }
    }
    public static final class Measure implements Step<Invoked,Measured> {
        public Measured execute(StepContext context,Invoked invoked) {
            try { return executeChecked(context,invoked); }
            catch (Exception e) { throw new IllegalStateException("Coverage stage failed",e); }
        }
        private Measured executeChecked(StepContext context,Invoked invoked) throws Exception {
            if(!invoked.outcome().workspaceState().equals("RETAINED")) throw new IllegalStateException("Quarantined workspace");
            var measured=new Measured(invoked,measure(invoked.prepared(),context.deadline()));
            CoverageEvidence.write(Path.of(invoked.prepared().task().evidenceRoot()).resolve("measured.json"),measured);
            return measured;
        }
    }
    /** Shared domain measurement, independent of provider invocation implementation. */
    static Domain measure(Prepared prepared, Instant deadline) throws Exception {
            Path root=Path.of(prepared.task().evidenceRoot()), ws=Path.of(prepared.item().workspace());
            var build=CoverageEvidence.build(ws,root.resolve("domain"),"final",CoverageEvidence.remaining(deadline,MEASUREMENT_RESERVE_MS),"clean","test","jacoco:report");
            var artifacts=new ArrayList<FileRef>(prepared.item().artifacts()); artifacts.add(build.log());
            var metrics=new ArrayList<Measurement>();
            metrics.add(Measurement.measured("finalBuildExitCode","exit-code",build.exitCode(),build.log().path(),prepared.item().identity().launchKey()));
            if(build.passed()) {
                var finalCov=CoverageEvidence.read(ws.resolve("target/site/jacoco/jacoco.xml"),root.resolve("domain/final.xml"));
                artifacts.add(finalCov.report()); metrics.addAll(CoverageEvidence.metrics(finalCov,prepared.item().identity().launchKey()));
            } else metrics.add(Measurement.unknown("lineCoverage","percent",build.timedOut()?"Final measurement timeout":"Final tests failed",prepared.item().identity().launchKey()));
            return new Domain(metrics,artifacts);
    }

}
