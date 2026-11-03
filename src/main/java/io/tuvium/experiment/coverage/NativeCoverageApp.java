package io.tuvium.experiment.coverage;

import io.github.markpollack.agents.client.*;
import io.github.markpollack.experiment.runtime.*;
import io.github.markpollack.experiment.runtime.AgentRecords.*;
import io.github.markpollack.workflow.flows.*;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel;
import io.github.markpollack.workflow.batch.durable.*;
import io.tuvium.experiment.coverage.CoverageAgentInvoker.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Pinned domain workflow using the installed Client/Experiment native path exactly once. */
public final class NativeCoverageApp {
    public record Invoked(Prepared prepared, Invocation invocation) {}
    public record Measured(Invoked invoked, Domain domain) {}
    private NativeCoverageApp() {}

    public static void main(String[] args) throws Exception {
        Map<String,String> options = new HashMap<>();
        for (int n=0;n<args.length;n+=2) {
            if (n+1>=args.length || options.put(args[n],args[n+1])!=null) throw new IllegalArgumentException("Unique option/value pairs required");
        }
        boolean candidate=options.containsKey("--codex-candidate-mode");
        boolean codexFixture=options.containsKey("--codex-fixture-mode");
        boolean codex=codexFixture || candidate;
        Set<String> required=candidate
                ? Set.of("--project-root","--output-dir","--run-id","--attempt-ledger","--codex-executable","--codex-candidate-mode","--outer-deadline")
                : codexFixture
                ? Set.of("--project-root","--output-dir","--run-id","--attempt-ledger","--codex-executable","--codex-fixture-mode","--outer-deadline")
                : Set.of("--project-root","--output-dir","--run-id","--attempt-ledger","--claude-executable","--outer-deadline");
        if (!options.keySet().equals(required))
            throw new IllegalArgumentException("Exact native launch options required");
        Path project=Path.of(options.get("--project-root")).toRealPath();
        Path requested=Path.of(options.get("--output-dir")).toAbsolutePath().normalize();
        Path evidence=requested.getParent().toRealPath().resolve(requested.getFileName());
        Path ledger=Path.of(options.get("--attempt-ledger")).toRealPath();
        Path executable=Path.of(options.get(codex?"--codex-executable":"--claude-executable")).toRealPath();
        Instant deadline=Instant.ofEpochMilli(Long.parseLong(options.get("--outer-deadline")));
        long remaining=Duration.between(Instant.now(),deadline).toMillis();
        if (remaining<=420_000 || remaining>1_785_000) throw new IllegalArgumentException("Bounded outer deadline required");
        if (evidence.startsWith(project) || !Files.isDirectory(evidence.getParent()))
            throw new IllegalArgumentException("New private output under an existing external parent required");
        if (!ExperimentApp.git(project,"diff","--name-only","HEAD").isEmpty())
            throw new IllegalStateException("Commit tracked source before native execution");
        String commit=ExperimentApp.git(project,"rev-parse","HEAD");
        NativeCoverageClient.requireMavenEnvironment(System.getProperty("coverage.mavenRepo"),candidate?"-Dmaven.repo.local="+System.getProperty("coverage.mavenRepo"):System.getenv("MAVEN_ARGS"));
        if(candidate) {
            if(!Set.of("fixture","native").contains(options.get("--codex-candidate-mode")))throw new IllegalArgumentException("Unknown candidate mode");
            var guard=new ProcessBuilder(System.getProperty("coverage.candidatePython"),System.getProperty("coverage.candidateGuard"),
                    "--manifest",System.getProperty("coverage.candidateManifest"),"--claim","--application",evidence.toString(),
                    "--executable",executable.toString(),"--candidate-mode",options.get("--codex-candidate-mode")).inheritIO().start();
            if(!guard.waitFor(90,TimeUnit.SECONDS)){guard.destroyForcibly();throw new IllegalStateException("Candidate guard timed out");}
            if(guard.exitValue()!=0)throw new IllegalStateException("Candidate guard refused exact approval/reservation");
        }
        Files.createDirectory(evidence);
        Process version=new ProcessBuilder(executable.toString(),"--version").redirectErrorStream(true)
                .redirectOutput(evidence.resolve("cli-version.txt").toFile()).start();
        if (!version.waitFor(10,TimeUnit.SECONDS)) {version.destroyForcibly(); throw new IllegalStateException("CLI version check timed out");}
        if (version.exitValue()!=0 || !Files.readString(evidence.resolve("cli-version.txt")).startsWith(codex?"codex-cli "+CodexCoverageClient.VERSION:NativeCoverageClient.CLI_VERSION+" "))
            throw new IllegalStateException("Selected CLI version differs");
        Path workspace=evidence.resolve("workspace");
        var pinned=PinnedCoverageTask.prepare(project,workspace);
        var policy=new CoverageEditPolicy(workspace); var before=policy.snapshot();
        var identity=Identity.of(options.get("--run-id"),"spring-petclinic-partial","simple",0);
        var provenance=new ArrayList<FileRef>();
        for (String entry:System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            Path jar=Path.of(entry); if (!Files.isRegularFile(jar) || !jar.toString().endsWith(".jar"))
                throw new IllegalStateException("Packaged application/runtime JARs required");
            provenance.add(Evidence.reference(jar,"runtime-jar"));
        }
        Path global=Path.of(System.getProperty("user.home"),".claude");
        if(!codex) {
        for(String name:List.of("settings.json","settings.local.json","CLAUDE.md")) {
            Path file=global.resolve(name);if(Files.isRegularFile(file)) provenance.add(Evidence.reference(file,"global-configuration"));
        }
        if(Files.isDirectory(global.resolve("skills"))) try(var files=Files.walk(global.resolve("skills"))) {
            for(Path file:files.filter(Files::isRegularFile).sorted().toList()) provenance.add(Evidence.reference(file,"global-skill"));
        }
        }
        provenance.add(Evidence.reference(executable,codex?"codex-executable":"claude-executable"));
        provenance.add(Evidence.reference(project.resolve("prompts/v0-simple.txt"),"pinned-prompt"));
        provenance.add(Evidence.reference(project.resolve("dataset/items/spring-petclinic-partial/item.json"),"pinned-item"));
        CoverageEvidence.write(evidence.resolve("launch.json"),Map.of("identity",identity,"sourceCommit",commit,"pinned",pinned,
                "outerDeadline",deadline.toString(),"subjectLimitMs",900000,"cleanupLimitMs",15000,"nativeCapUsd",codex?"UNSUPPORTED":5,
                "semanticQuality","NOT_ASSESSED","inputs",provenance));
        var task=new Task(identity,workspace.toString(),Files.readString(project.resolve("prompts/v0-simple.txt"))+
                (candidate?"\n\nChange only src/test/**. Build output under target is excluded from the accepted patch. Your final test patch is independently rebuilt in a fresh pinned copy. Do not alter other inputs.":""),candidate?"gpt-6-luna":codexFixture?CodexCoverageClient.MODEL:NativeCoverageClient.MODEL,evidence.toString());
        var candidateWorkspace=candidate?new CodexCandidateWorkspace(project,workspace,evidence):null;
        var active=new AtomicReference<AgentExecution>();
        var invoke=new Invoke(active,evidence,ledger,deadline,before,codex);
        var prepare=new Prepare(); var measure=new Measure(candidateWorkspace);
        var graph=Workflows.define("coverage-v3-native").maxDuration(Duration.ofMillis(remaining-420_000))
                .then("prepare-baseline",prepare).then("invoke-native",invoke).then("measure-final",measure)
                .terminate(WorkflowModel.Terminal.SUCCEEDED).build();
        var registry=StepRegistry.builder().register("coverage-native-prepare-v1",prepare)
                .register("coverage-native-invoke-v1",invoke).register("coverage-native-measure-v1",measure).build();
        var compatibility=new ExecutionCompatibility("coverage-v3-native",commit,Map.of("launchSha256",Evidence.sha256(evidence.resolve("launch.json"))));
        WorkflowAdapter.Execution execution;
        try (var client=candidate?("fixture".equals(options.get("--codex-candidate-mode"))
                ?CodexCoverageClient.openFixture(workspace,evidence,executable.toString(),"success",true)
                :CodexCoverageClient.openCandidate(workspace,evidence,executable.toString())):codexFixture?CodexCoverageClient.openFixture(workspace,evidence,executable.toString(),options.get("--codex-fixture-mode"),true)
                : NativeCoverageClient.open(workspace,evidence,executable.toString())) {
            invoke.service=new AsyncClientInvocationService(client);
            execution=new WorkflowAdapter().execute(evidence.resolve("workflow"),graph,registry,compatibility,identity,task,
                    NativeCoverageClient.CLEANUP_LIMIT,()->{var handle=active.get(); if(handle!=null) handle.cancel(false);});
        }
        PreparedItem prepared=new PreparedItem(identity,workspace.toString(),List.of(),List.of());
        Domain domain=new Domain(List.of(),List.of()); var invocations=new ArrayList<Invocation>();
        if (execution.accepted()) {
            var measured=(Measured)execution.value(); prepared=measured.invoked().prepared().item(); domain=measured.domain();
            invocations.add(measured.invoked().invocation());
        } else {
            if (Files.exists(evidence.resolve("prepared.json")))
                prepared=CoverageEvidence.JSON.readValue(evidence.resolve("prepared.json").toFile(),Prepared.class).item();
            if (Files.exists(evidence.resolve("invoked.json")))
                invocations.add(CoverageEvidence.JSON.readValue(evidence.resolve("invoked.json").toFile(),Invoked.class).invocation());
        }
        Row row=WorkflowAdapter.assess(execution,prepared,invocations,domain,
                (item,measured)->candidateWorkspace==null
                    ?DeterministicCoverageJury.create(item,measured,policy,before,evidence.resolve("judges"),deadline.minusSeconds(120))
                    :DeterministicCoverageJury.create(new PreparedItem(item.identity(),candidateWorkspace.verification.toString(),item.baseline(),item.artifacts()),
                        measured,candidateWorkspace.verificationPolicy,candidateWorkspace.verificationBefore,evidence.resolve("judges"),deadline.minusSeconds(120)));
        if(candidateWorkspace!=null && execution.accepted()) candidateWorkspace.recheck();
        var result=new Result(AgentRecords.CURRENT_SCHEMA_VERSION,"AGENT_CANONICAL",identity.runId(),"coverage-v3-native-validation",
                System.currentTimeMillis(),List.of(row),provenance);
        var store=new FileSystemResultStore(evidence.resolve("results")); store.save(result);
        if (!store.load(identity.runId()).orElseThrow().equals(result)) throw new IllegalStateException("Immutable result readback differs");
        CoverageEvidence.write(evidence.resolve("completion.json"),Map.of("workflow",row.workflowState(),"judge",row.judgeState(),
                "semanticQuality","NOT_ASSESSED","nativeSubmissionReserved",Files.exists(evidence.resolve("submission.json")),
                "actualCostState","UNKNOWN","nativeCapUsd",codex?"UNSUPPORTED":5));
        if (!execution.accepted() || row.invocations().size()!=1 || !row.judgeState().equals("RETURNED"))
            throw new IllegalStateException("Native attempt incomplete; preserve raw attempt and never replay implicitly");
    }

    static class Invoke implements Step<Prepared,Invoked> {
        AsyncClientInvocationService service;
        final AtomicReference<AgentExecution> active; final Path evidence, ledger; final Instant outerDeadline;
        final CoverageEditPolicy.Snapshot initial; final boolean codexFixture;
        Invoke(AtomicReference<AgentExecution> active,Path evidence,Path ledger,Instant outerDeadline,CoverageEditPolicy.Snapshot initial) {
            this(active,evidence,ledger,outerDeadline,initial,false);
        }
        Invoke(AtomicReference<AgentExecution> active,Path evidence,Path ledger,Instant outerDeadline,CoverageEditPolicy.Snapshot initial,boolean codexFixture) {
            this.active=active;this.evidence=evidence;this.ledger=ledger;this.outerDeadline=outerDeadline;this.initial=initial;this.codexFixture=codexFixture;
        }
        public Invoked execute(StepContext context,Prepared prepared) {
            long start=System.nanoTime();
            AsyncClientInvocationService.StartedExecution started=null;
            ExecutionTermination termination=null; Attempt attempt=null; Exception failure=null;
            NativeAttemptCustody.ObservedResponse observedResponse=null;
            try {
                long budget=Math.min(900000,CoverageAgentInvoker.providerBudget(context.deadline()));
                attempt=new Attempt(prepared.item().identity(),UUID.randomUUID().toString(),context.runId(),
                        context.invocationId(),context.attemptId(),context.attemptNumber());
                var preparationPolicy=new CoverageEditPolicy(Path.of(prepared.item().workspace()));
                var preparationViolations=preparationPolicy.violations(initial);
                CoverageEvidence.write(evidence.resolve("preparation-edit-surface.json"),Map.of("violations",preparationViolations));
                if(!preparationViolations.isEmpty()) throw new IllegalStateException("Preparation changed protected task inputs");
                var preparedSnapshot=preparationPolicy.snapshot();
                CoverageEvidence.write(evidence.resolve("prepared-protected-snapshot.json"),preparedSnapshot);
                CoverageEvidence.write(evidence.resolve("expanded-prompt-provenance.json"),Map.of(
                        "basePromptSha256",Evidence.sha256(prepared.task().prompt().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        "expandedPromptSha256",Evidence.sha256(prepared.expandedPrompt().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        "basis","Pinned base prompt plus existing deterministic baseline metrics"));
                if(!codexFixture) NativeCoverageLedger.reserve(ledger,attempt.row().runId());
                else {
                    var fixtureLedger=CoverageEvidence.JSON.readTree(ledger.toFile());
                    if(!Set.of("v3-codex-fixture-only/1","v3-codex-candidate-reserved/1").contains(fixtureLedger.path("kind").asText()))
                        throw new IllegalStateException("Separate synthetic fixture ledger required");
                }
                CoverageEvidence.write(evidence.resolve("submission.json"),Map.of("attempt",attempt,"providerBudgetMs",budget,
                        "reservedUsd",codexFixture?"UNSUPPORTED":5,"actualCostState","UNKNOWN","startedAt",Instant.now().toString()));
                started=service.start(attempt,Path.of(prepared.item().workspace()),prepared.expandedPrompt(),Duration.ofMillis(budget));
                var handle=started.execution(); active.set(handle);
                AgentClientResponse response=null; Exception responseFailure=null;
                try {response=handle.result();observedResponse=NativeAttemptCustody.returned(started,response);}
                catch (Exception responseError) {responseFailure=responseError;observedResponse=NativeAttemptCustody.threw(started,responseError);}
                termination=handle.awaitTermination(NativeCoverageClient.CLEANUP_LIMIT);
                long recordingDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(35);
                while (!(handle.recording().delivery() instanceof TerminalDelivery.Completed)
                        && !(handle.recording().delivery() instanceof TerminalDelivery.Failed)
                        && System.nanoTime()<recordingDeadline && Instant.now().isBefore(outerDeadline)) Thread.sleep(10);
                if(responseFailure!=null) throw responseFailure;
                NativeAttemptCustody.retainResponse(evidence.resolve("observed-client-response.json"),observedResponse);
                if(response==null || !response.isSuccessful()) throw new IllegalStateException("Native response unsuccessful");
                if(!(handle.recording().delivery() instanceof TerminalDelivery.Completed completed))
                    throw new IllegalStateException("Native recording incomplete");
                Path events=selectPrepared(evidence.resolve("journal"),completed.bindingId(),handle.executionId(),started.decision().orElseThrow().decisionId());
                var invocation=accept(started,termination,events,budget,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
                var invoked=new Invoked(prepared,invocation);CoverageEvidence.write(evidence.resolve("invoked.json"),invoked);return invoked;
            } catch (Exception problem) {
                failure=problem;
                throw new IllegalStateException("Native coverage invocation failed",problem);
            } finally {
                if(attempt!=null && Files.exists(evidence.resolve("submission.json"))) try {
                    NativeAttemptCustody.retain(evidence.resolve("native-attempt.json"),attempt,started,termination,observedResponse,failure);
                } catch(Exception custodyFailure) {
                    if(failure!=null) failure.addSuppressed(custodyFailure);
                    else throw new IllegalStateException("Native attempt custody failed",custodyFailure);
                }
            }
        }
        Invocation accept(AsyncClientInvocationService.StartedExecution started, ExecutionTermination termination,
                Path events, long budget, long elapsed) throws Exception {
            String execution=started.execution().executionId();
            return NativeClientInvocation.accept(started,termination,codexFixture?CodexCoverageClient.originalManifest(evidence):evidence.resolve("sources").resolve(execution).resolve("acquisition.json"),
                    evidence.resolve("journal/native-"+execution),events,budget,elapsed);
        }
    }
    static Path selectPrepared(Path journal,String binding,String execution,String decision) throws Exception {
        List<Path> matches=new ArrayList<>();
        try(var paths=Files.walk(journal.resolve("prepared"),2)) {
            for(Path marker:paths.filter(p->p.getFileName().toString().equals("marker.json")).toList()) {
                var ack=CoverageEvidence.JSON.readTree(marker.toFile()).path("ack");
                if(!binding.equals(ack.path("bindingId").asText())) continue;
                if(!execution.equals(ack.path("executionId").asText()) || !decision.equals(ack.path("decisionId").asText()))
                    throw new IllegalStateException("Receiver ACK identity differs");
                for(var ref:ack.path("artifacts")) {
                    Path p=journal.resolve(ref.path("relativePath").asText()).normalize();
                    if(p.equals(marker.getParent().resolve("events.jsonl")) && p.toRealPath().equals(p)
                            && Evidence.sha256(p).equals(ref.path("sha256").asText()) && Files.size(p)==ref.path("size").asLong()) matches.add(p);
                }
            }
        }
        if(matches.size()!=1) throw new IllegalStateException("Expected one actual ACK-selected canonical events file");
        return matches.getFirst();
    }
    static final class Measure implements Step<Invoked,Measured> {
        final CodexCandidateWorkspace candidate;
        Measure(){this(null);}
        Measure(CodexCandidateWorkspace candidate){this.candidate=candidate;}
        public Measured execute(StepContext context,Invoked invoked) {
            try {
                var prepared=candidate==null?invoked.prepared():candidate.transfer(invoked.prepared());
                if(candidate!=null)candidate.snapshotVerification();
                var result=new Measured(invoked,CoverageAgentInvoker.measure(prepared,context.deadline()));
                CoverageEvidence.write(Path.of(invoked.prepared().task().evidenceRoot()).resolve("measured.json"),result);return result;
            } catch(Exception failure) {throw new IllegalStateException("Native domain measurement failed",failure);}
        }
    }
}
