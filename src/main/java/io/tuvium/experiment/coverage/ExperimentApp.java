package io.tuvium.experiment.coverage;

import io.github.markpollack.experiment.runtime.*;
import io.github.markpollack.experiment.runtime.AgentRecords.*;
import io.github.markpollack.workflow.flows.*;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel;
import io.github.markpollack.workflow.batch.durable.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.yaml.snakeyaml.Yaml;
import io.tuvium.experiment.coverage.CoverageAgentInvoker.*;

/** One immutable, real PetClinic attempt through shared Experiment and released Workflow. */
public final class ExperimentApp {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        var options=new HashMap<String,String>();
        for(int i=0;i<args.length;i+=2) {if(i+1>=args.length) throw new IllegalArgumentException("Expected option value"); options.put(args[i],args[i+1]);}
        for(String key:options.keySet()) if(!Set.of("--variant","--output-dir","--replicate","--run-id","--project-root").contains(key)) throw new IllegalArgumentException("Unknown option "+key);
        String variant=Objects.requireNonNull(options.get("--variant"),"--variant required");
        Path project=Path.of(options.getOrDefault("--project-root",".")).toAbsolutePath().normalize();
        Path evidence=Path.of(Objects.requireNonNull(options.get("--output-dir"),"private --output-dir required")).toAbsolutePath().normalize();
        if(evidence.startsWith(project)) throw new IllegalArgumentException("Evidence must be outside public source tree");
        if(System.getProperty("coverage.mavenRepo")==null) throw new IllegalArgumentException("Set -Dcoverage.mavenRepo to isolated Maven repository");
        Map<String,Object> config=new Yaml().load(Files.readString(project.resolve("experiment-config.yaml")));
        Map<String,Object> selected=((List<Map<String,Object>>)config.get("variants")).stream().filter(v->variant.equals(v.get("name"))).findFirst().orElseThrow();
        if(selected.get("knowledgeDir")!=null || !((List<?>)selected.get("knowledgeFiles")).isEmpty()) throw new IllegalArgumentException("Unqualified knowledge configuration");
        String model=(String)config.get("defaultModel"); int minutes=((Number)config.get("timeoutMinutes")).intValue();
        if(!model.equals("claude-sonnet-4-6")||minutes!=30) throw new IllegalArgumentException("This qualified path requires the pinned model and total 30-minute budget");
        String commit=git(project,"rev-parse","HEAD");
        var cliProcess=new ProcessBuilder("claude","--version").redirectErrorStream(true).start();
        String cliVersion=new String(cliProcess.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).trim();
        if(cliProcess.waitFor()!=0 || !cliVersion.startsWith("2.1.316 ")) throw new IllegalStateException("Unqualified Claude CLI: "+cliVersion);
        if(!git(project,"status","--porcelain").isEmpty()) throw new IllegalStateException("Commit source before measured execution");
        Files.createDirectory(evidence); Files.createDirectory(evidence.resolve("attempts"));
        Path ws=evidence.resolve("workspace"); copyTree(project.resolve("dataset/items/spring-petclinic-partial/before"),ws);
        String run=options.getOrDefault("--run-id","v3-"+Instant.now().toString().replace(":","-")+"-"+variant);
        var identity=Identity.of(run,"spring-petclinic-partial",variant,Integer.parseInt(options.getOrDefault("--replicate","0")));
        var provenance=new ArrayList<FileRef>();
        for(String path:List.of("experiment-config.yaml","prompts/"+selected.get("promptFile"),"prompts/judge-practice-adherence.txt")) provenance.add(Evidence.reference(project.resolve(path),"launch-input"));
        Path skills=Path.of(System.getProperty("user.home"),".claude/skills");
        for(String skill:List.of("spring-mvc-testing","spring-jpa-testing","spring-testing-fundamentals","spring-security-testing","spring-webflux-testing","spring-websocket-testing")) {
            try(var files=Files.walk(skills.resolve(skill))) {for(Path p:files.filter(Files::isRegularFile).sorted().toList()) provenance.add(Evidence.reference(p,"global-skill"));}
        }
        for(String file:List.of(".claude/settings.json",".claude/settings.local.json",".claude/CLAUDE.md")) {
            Path p=Path.of(System.getProperty("user.home")).resolve(file);
            if(Files.isRegularFile(p)) provenance.add(Evidence.reference(p,"global-configuration"));
        }
        for(String cp:System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            Path p=Path.of(cp); if(!Files.isRegularFile(p)) throw new IllegalStateException("Run packaged JARs, not source/classes: "+p);
            provenance.add(Evidence.reference(p,"runtime-jar"));
        }
        CoverageEvidence.write(evidence.resolve("launch.json"),Map.of("identity",identity,"sourceCommit",commit,"model",model,"itemBudgetMs",minutes*60_000L,
                "providerPolicy","remaining item deadline minus 300000ms final measurement and 30000ms drain; no implicit retry",
                "judgeBudgets","T0 10 minutes; T2 3 minutes, separate from invocation","inputs",provenance,"javaVersion",System.getProperty("java.version"),"startedAt",Instant.now().toString()));
        var task=new Task(identity,ws.toString(),Files.readString(project.resolve("prompts").resolve((String)selected.get("promptFile"))),model,evidence.toString());
        var service=new ClaudeInvocationService("2.1.316",Path.of(System.getProperty("user.home"),".claude/projects"),Duration.ofSeconds(15),AcquisitionAssembly.journal());
        var prepare=new Prepare(); var invoke=new Invoke(service); var measure=new Measure();
        var graph=Workflows.define("coverage-v3").maxDuration(Duration.ofMinutes(minutes)).then("prepare-baseline",prepare).then("invoke-agent",invoke).then("measure-final",measure).terminate(WorkflowModel.Terminal.SUCCEEDED).build();
        var registry=StepRegistry.builder().register("coverage-prepare-v1",prepare).register("coverage-invoke-v1",invoke).register("coverage-measure-v1",measure).build();
        var deployment=new ExecutionCompatibility("coverage-v3",commit,Map.of("model",model,"itemBudgetMs",Long.toString(minutes*60_000L),"launchSha256",Evidence.sha256(evidence.resolve("launch.json"))));
        System.out.println("START "+identity.runId()+" evidence="+evidence);
        var execution=new WorkflowAdapter().execute(evidence.resolve("workflow"),graph,registry,deployment,identity,task,Duration.ofSeconds(15),service::terminateActive);
        PreparedItem prepared=new PreparedItem(identity,ws.toString(),List.of(),List.of());
        Domain domain=new Domain(List.of(),List.of()); var invocations=new ArrayList<Invocation>();
        if(execution.accepted()) {
            var result=(Measured)execution.value(); prepared=result.invoked().prepared().item(); domain=result.domain(); invocations.add(result.invoked().outcome().invocation());
        } else {
            if(Files.exists(evidence.resolve("prepared.json"))) prepared=CoverageEvidence.JSON.readValue(evidence.resolve("prepared.json").toFile(),Prepared.class).item();
            try(var paths=Files.list(evidence.resolve("attempts"))) {
                for(Path attempt:paths.toList()) if(Files.exists(attempt.resolve("outcome.json"))) invocations.add(ClaudeInvocationService.readOutcome(attempt).invocation());
            }
        }
        Row row=WorkflowAdapter.assess(execution,prepared,invocations,domain,(item,measured)->JuryFactory.create(item,measured,project.resolve("prompts/judge-practice-adherence.txt"),evidence.resolve("judges")));
        var result=new Result(AgentRecords.CURRENT_SCHEMA_VERSION,"AGENT_CANONICAL",run,(String)config.get("experimentName"),System.currentTimeMillis(),List.of(row),provenance);
        var store=new FileSystemResultStore(evidence.resolve("results")); store.save(result);
        if(!store.load(run).orElseThrow().equals(result)) throw new IllegalStateException("Result round trip differs");
        Evidence.verify(row);
        System.out.println("FINISH "+run+" workflow="+row.workflowState()+" judge="+row.judgeState()+" verdict="+(row.verdicts().isEmpty()?"UNAVAILABLE":row.verdicts().getFirst().conclusion()));
        // A domain FAIL is retained as a valid observed outcome; independent capture/reader qualification follows.
    }
    static void copyTree(Path source,Path destination) throws Exception {
        try(var walk=Files.walk(source)) {for(Path p:walk.toList()) {Path target=destination.resolve(source.relativize(p)); if(Files.isDirectory(p)) Files.createDirectories(target); else Files.copy(p,target,StandardCopyOption.COPY_ATTRIBUTES);}}
    }
    static String git(Path directory,String... args) throws Exception {
        var cmd=new ArrayList<String>(List.of("git"));cmd.addAll(List.of(args));
        var p=new ProcessBuilder(cmd).directory(directory.toFile()).redirectErrorStream(true).start();
        String output=new String(p.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).trim();
        if(p.waitFor()!=0) throw new IllegalStateException(output);return output;
    }
}
