package io.tuvium.experiment.coverage.judge;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import io.github.markpollack.agents.client.AgentClient;
import io.github.markpollack.agents.claude.*;
import io.github.markpollack.judge.Judge;
import io.github.markpollack.judge.judgment.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Original six-criterion rubric, with strict instrument validation. */
public final class TestQualityJudge implements Judge {
    public static final List<String> CRITERIA=List.of("test_slice_selection","assertion_quality","error_and_edge_case_coverage","domain_specific_test_patterns","coverage_target_selection","version_aware_patterns");
    private static final ObjectMapper JSON=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Path workspace,prompt,evidence;
    public TestQualityJudge(Path workspace,Path prompt,Path evidence) {this.workspace=workspace;this.prompt=prompt;this.evidence=evidence;}
    public Judgment judge() {
        try {
            Files.createDirectories(evidence);
            try(var files=Files.walk(workspace.resolve("src/test"))) {
                if(files.noneMatch(p->Files.isRegularFile(p)&&p.toString().endsWith(".java"))) return emptySuite();
            }
            String rubric=Files.readString(prompt);
            Files.writeString(evidence.resolve("prompt.txt"),rubric,StandardOpenOption.CREATE_NEW);
            var options=ClaudeAgentOptions.builder().model("claude-sonnet-4-6")
                    .allowedTools(List.of("Read","Glob","Grep")).yolo(false).timeout(Duration.ofMinutes(3)).build();
            try(var model=ClaudeAgentModel.builder().workingDirectory(workspace).timeout(Duration.ofMinutes(3))
                    .capture(new ClaudeCaptureOptions(evidence.resolve("acquired"),"quality-"+UUID.randomUUID(),"2.1.316"))
                    .defaultOptions(options).build()) {
                // Pass provider options through the facade as well: portable defaults otherwise override yolo(false).
                var response=AgentClient.builder(model).defaultOptions(options).build().goal(rubric).workingDirectory(workspace).run();
                String text=Objects.toString(response.getResult(),"");
                Files.writeString(evidence.resolve("response.txt"),text,StandardOpenOption.CREATE_NEW);
                if(!response.isSuccessful()) return Judgment.error("Quality agent did not complete successfully");
                return parseJudgment(text);
            }
        } catch(Exception e) {return Judgment.error("Quality instrument failure: " + e);}
    }
    private static Judgment emptySuite() {
        var checks=CRITERIA.stream().map(name->new Check(name,
                Judgment.builder().fail().score(0.0).reasoning("no test classes found").build())).toList();
        return Judgment.builder().fail().score(0.0).reasoning("no test classes found").checks(checks)
                .metadata("criteria_count",6).metadata("convergence_met",false).build();
    }
    public static Judgment parseJudgment(String text) {
        try {
            // CLI may wrap its one JSON object in a markdown fence. Reject additional JSON/trailing prose inside it.
            int start=text.indexOf('{'),end=text.lastIndexOf('}');
            if(start<0||end<start) throw new IllegalArgumentException("No rubric JSON");
            var root=JSON.readTree(text.substring(start,end+1));
            var criteria=root.get("criteria");
            if(criteria==null||!criteria.isArray()||criteria.size()!=6) throw new IllegalArgumentException("Exactly six criteria required");
            if(!root.path("boot_version").isTextual()||root.path("boot_version").asText().isBlank()) throw new IllegalArgumentException("Boot version required");
            var seen=new HashSet<String>(); var checks=new ArrayList<Check>(); java.math.BigDecimal total=java.math.BigDecimal.ZERO;
            for(var c:criteria) {
                String name=c.path("name").asText();
                if(!CRITERIA.contains(name)||!seen.add(name)) throw new IllegalArgumentException("Unknown/duplicate criterion: " + name);
                var scoreNode=c.get("score");
                if(scoreNode==null||!scoreNode.isNumber()) throw new IllegalArgumentException("Numeric score required");
                double score=scoreNode.doubleValue();
                if(!Double.isFinite(score)||score<0||score>1) throw new IllegalArgumentException("Finite score in 0..1 required");
                if(!c.path("evidence").isTextual()||c.path("evidence").asText().isBlank()) throw new IllegalArgumentException("Criterion evidence required");
                checks.add(new Check(name,(score>=0.5?Judgment.builder().pass():Judgment.builder().fail()).score(score).reasoning(c.path("evidence").asText()).build())); total=total.add(java.math.BigDecimal.valueOf(score));
            }
            double average=total.divide(java.math.BigDecimal.valueOf(6),java.math.MathContext.DECIMAL64).doubleValue();
            return (average>=0.5?Judgment.builder().pass():Judgment.builder().fail()).score(average).checks(checks)
                    .reasoning("Six-criterion practice adherence; executable threshold 0.5; convergence target 0.80")
                    .metadata("boot_version",root.path("boot_version").asText()).metadata("criteria_count",6)
                    .metadata("convergence_met",average>=0.80).metadata("raw_json",root.toString()).build();
        } catch(Exception e) {return Judgment.error("Invalid quality instrument output: " + e.getMessage());}
    }
}
