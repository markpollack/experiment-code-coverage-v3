package io.tuvium.experiment.coverage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import io.github.markpollack.agents.client.*;
import io.github.markpollack.agents.client.journal.JournalExecutionRecorder;
import io.github.markpollack.agents.codex.*;
import io.github.markpollack.experiment.runtime.Evidence;
import io.github.markpollack.journal.ocsf.writer.*;
import io.github.markpollack.journal.tck.ocsf.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;

/** Explicit Codex consumer composition. Native admission awaits an enforceable task policy. */
public final class CodexCoverageClient {
    public static final String VERSION = "0.166.0";
    public static final String MODEL = "synthetic-model";
    private CodexCoverageClient() {}

    public static AgentClient openNative(Path workspace, Path evidence, String executable) {
        throw new UnsupportedOperationException("Codex native V3 policy is unqualified: test-only writes and exact Maven command enforcement required");
    }

    /** Only the packaged local child can enter this route; a mode label cannot admit a native CLI. */
    public static AgentClient openFixture(Path workspace, Path evidence, String executable, String mode) throws Exception {
        if (!Set.of("success", "failed", "wrong").contains(mode)) throw new IllegalArgumentException("Unknown fixture mode");
        try (var fixture = CodexCoverageClient.class.getResourceAsStream("/codex-child.py")) {
            if (fixture == null || !Evidence.sha256(fixture.readAllBytes()).equals(Evidence.sha256(Path.of(executable))))
                throw new IllegalArgumentException("Fixture executable differs from packaged local child");
        }
        try (var template=CodexCoverageClient.class.getResourceAsStream("/codex-native-template.jsonl")) {
            if(template==null || !Evidence.sha256(template.readAllBytes()).equals(Evidence.sha256(Path.of(executable).resolveSibling("codex-native-template.jsonl"))))
                throw new IllegalArgumentException("Fixture native template differs from packaged local template");
        }
        Path sessions = evidence.resolve("codex-sessions");
        var provider = CodexClientProvider.builder().codexPath(executable).workingDirectory(workspace)
                .nativeSessionsDirectory(sessions).acquisitionDirectory(evidence.resolve("sources"), VERSION)
                .skipGitCheck(true).build();
        var options = DefaultAgentOptions.builder().workingDirectory(workspace.toString()).model(MODEL).effort("low")
                .environmentVariables(Map.of("FIXTURE_SESSIONS",sessions.toString(),"V3_CODEX_EVIDENCE",evidence.toString(),
                        "V3_CODEX_FIXTURE_MODE",mode)).build();
        return AgentClient.builder(provider).defaultOptions(options).defaultWorkingDirectory(workspace)
                .defaultTimeout(NativeCoverageClient.SUBJECT_LIMIT).cleanupTimeout(NativeCoverageClient.CLEANUP_LIMIT)
                .executionRecorder(new Recorder(evidence)).build();
    }

    static Path originalManifest(Path evidence) throws Exception {
        var ref=CoverageEvidence.JSON.readValue(evidence.resolve("codex-original-acquisition-reference.json").toFile(),
                io.github.markpollack.experiment.runtime.AgentRecords.FileRef.class);
        Evidence.verify(ref);return Path.of(ref.path());
    }

    private static final class Recorder implements ExecutionRecorder {
        private final Path evidence, originals;
        private final JournalExecutionRecorder original;
        Recorder(Path evidence) throws Exception {
            this.evidence=evidence;this.originals=evidence.resolve("original-decision-store");
            this.original=new JournalExecutionRecorder(originals,"v3-codex");
        }
        public Supplier<String> prepare(ExecutionDecision decision) { throw new IllegalStateException("Original Codex acquisition required"); }
        public Supplier<String> prepare(ExecutionDecision decision, Map<String,Object> values) {
            var source=(CodexAcquisition)values.get("codexAcquisition");
            var deliver=original.prepare(decision);
            return () -> {
                String originalBinding=deliver.get();
                try {
                    if(source==null || !source.complete()) throw new IllegalStateException("Original Codex acquisition incomplete");
                    Path sealed=source.writeManifest(decision);
                    CoverageEvidence.write(evidence.resolve("codex-original-acquisition-reference.json"),Evidence.reference(sealed,"original-codex-acquisition"));
                    Files.createDirectories(evidence.resolve("journal"));
                    Path qualifiedRoot=evidence.resolve("journal/native-"+decision.executionId());
                    var qualified=io.github.markpollack.journal.tck.ocsf.inventory.CodexAcquisition.qualify(sealed,qualifiedRoot);
                    Path events=NativeCoverageApp.selectPrepared(originals,originalBinding,decision.executionId(),decision.decisionId());
                    var pkg=PackageReader.open(events);var binding=TerminalBindingReader.read(pkg.declarations);
                    var json=CoverageEvidence.JSON;
                    var envelope=(ObjectNode)json.readTree(pkg.declarations.run().member("terminal_binding").member("terminalEnvelope").text());
                    var association=(ObjectNode)json.readTree(binding.association());
                    var manifest=json.readTree(qualified.manifest().toFile());
                    association.put("acquisition","COMPLETED");association.putNull("priorRevisionId");association.putArray("missing");
                    ArrayNode sources=association.putArray("sources");
                    for(var item:manifest.required("sources")) sources.add(occurrence(qualifiedRoot,item,manifest,decision));
                    sources.add(occurrence(qualifiedRoot,json.createObjectNode().put("path","acquisition.json")
                            .put("role","manifest").put("origin","qualified-codex-manifest"),manifest,decision));
                    envelope.put("profile","client-terminal-codex-0.166.0/1");
                    envelope.put("associationBytes",Base64.getEncoder().encodeToString(canonical(association)));
                    Files.write(evidence.resolve("codex-terminal-envelope.json"),canonical(envelope),StandardOpenOption.CREATE_NEW);
                    var receiver=new TerminalReceiver(evidence.resolve("journal"),"v3-codex",WriterOptions.defaults());
                    var ack=json.readTree(receiver.receive(canonical(envelope),qualifiedRoot));
                    return ack.required("bindingId").asText();
                } catch(Exception failure) {
                    try {var diagnostic=new java.io.StringWriter();failure.printStackTrace(new java.io.PrintWriter(diagnostic));
                        Files.writeString(evidence.resolve("codex-archive-failure.txt"),diagnostic.toString(),StandardOpenOption.CREATE_NEW);}
                    catch(Exception custodyFailure) {failure.addSuppressed(custodyFailure);}
                    throw new IllegalStateException("Codex original-D archive delivery failed: "+failure,failure);
                }
            };
        }
    }
    private static ObjectNode occurrence(Path base,JsonNode item,JsonNode manifest,ExecutionDecision decision) throws Exception {
        String path=item.required("path").asText();byte[] bytes=Files.readAllBytes(base.resolve(path));
        String stream=Map.of("stdin","STDIN","stream","STDOUT","stderr","STDERR").get(item.required("role").asText());
        return CoverageEvidence.JSON.createObjectNode().put("sourceId",path).put("relativePath",path)
                .put("origin",item.required("origin").asText()).put("sha256",Evidence.sha256(bytes)).put("size",bytes.length)
                .put("start",0).put("end",bytes.length).put("stream",stream)
                .put("nativeSessionId",manifest.required("run").required("session_uid").asText()).putNull("nativeTurnId")
                .putNull("nativeConnectionId").put("clientConnectionId",decision.connectionId().orElseThrow());
    }
    private static byte[] canonical(JsonNode value) throws Exception { return CoverageEvidence.JSON.writeValueAsBytes(sorted(value)); }
    private static JsonNode sorted(JsonNode value) {
        if(value.isObject()) {var out=CoverageEvidence.JSON.createObjectNode();var fields=new TreeMap<String,JsonNode>();
            value.fields().forEachRemaining(e->fields.put(e.getKey(),e.getValue()));fields.forEach((key,v)->out.set(key,sorted(v)));return out;}
        if(value.isArray()) {var out=CoverageEvidence.JSON.createArrayNode();value.forEach(v->out.add(sorted(v)));return out;}
        return value;
    }
}
