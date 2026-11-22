package io.tuvium.experiment.coverage;

import io.github.markpollack.agents.client.*;
import io.github.markpollack.agents.client.journal.JournalExecutionRecorder;
import io.github.markpollack.agents.client.codex.*;
import io.github.markpollack.experiment.runtime.Evidence;
import java.nio.file.*;
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

    static AgentClient openCandidate(Path workspace, Path evidence, String executable) throws Exception {
        if (!Files.isRegularFile(evidence.getParent().resolve("candidate-reservation.json")))
            throw new IllegalStateException("Exact candidate guard reservation required");
        var provider=CodexClientProvider.builder().codexPath(executable).workingDirectory(workspace)
                .nativeSessionsDirectory(Path.of(System.getenv("CODEX_HOME"),"sessions"))
                .acquisitionDirectory(evidence.resolve("sources"),VERSION)
                .sandbox(io.github.markpollack.agents.codexsdk.types.SandboxMode.WORKSPACE_WRITE).skipGitCheck(true).build();
        var options=DefaultAgentOptions.builder().workingDirectory(workspace.toString()).model("gpt-6-luna").effort("high").build();
        return AgentClient.builder(provider).defaultOptions(options).defaultWorkingDirectory(workspace)
                .defaultTimeout(NativeCoverageClient.SUBJECT_LIMIT).cleanupTimeout(NativeCoverageClient.CLEANUP_LIMIT)
                .executionRecorder(new Recorder(evidence)).build();
    }

    /** Only the packaged local child can enter this route; a mode label cannot admit a native CLI. */
    public static AgentClient openFixture(Path workspace, Path evidence, String executable, String mode) throws Exception {
        return openFixture(workspace,evidence,executable,mode,false);
    }

    static AgentClient openFixture(Path workspace,Path evidence,String executable,String mode,boolean taskEdits) throws Exception {
        if (!Set.of("success", "failed", "wrong").contains(mode)) throw new IllegalArgumentException("Unknown fixture mode");
        try (var fixture = CodexCoverageClient.class.getResourceAsStream("/codex-child.py")) {
            if (fixture == null || !Evidence.sha256(fixture.readAllBytes()).equals(Evidence.sha256(Path.of(executable))))
                throw new IllegalArgumentException("Fixture executable differs from packaged local child");
        }
        try (var template=CodexCoverageClient.class.getResourceAsStream("/codex-native-template.jsonl")) {
            if(template==null || !Evidence.sha256(template.readAllBytes()).equals(Evidence.sha256(Path.of(executable).resolveSibling("codex-native-template.jsonl"))))
                throw new IllegalArgumentException("Fixture native template differs from packaged local template");
        }
        if(taskEdits) verifyEditCorpus(Path.of(executable).getParent());
        Path sessions = evidence.resolve("codex-sessions");
        var provider = CodexClientProvider.builder().codexPath(executable).workingDirectory(workspace)
                .nativeSessionsDirectory(sessions).acquisitionDirectory(evidence.resolve("sources"), VERSION)
                .skipGitCheck(true).build();
        var options = DefaultAgentOptions.builder().workingDirectory(workspace.toString()).model(MODEL).effort("low")
                .environmentVariables(Map.of("FIXTURE_SESSIONS",sessions.toString(),"V3_CODEX_EVIDENCE",evidence.toString(),
                        "V3_CODEX_FIXTURE_MODE",mode,"V3_CODEX_TASK_EDITS",Boolean.toString(taskEdits))).build();
        return AgentClient.builder(provider).defaultOptions(options).defaultWorkingDirectory(workspace)
                .defaultTimeout(NativeCoverageClient.SUBJECT_LIMIT).cleanupTimeout(NativeCoverageClient.CLEANUP_LIMIT)
                .executionRecorder(new Recorder(evidence)).build();
    }

    private static void verifyEditCorpus(Path directory) throws Exception {
        byte[] expected;
        try(var in=CodexCoverageClient.class.getResourceAsStream("/codex-petclinic-tests.json")) {expected=in.readAllBytes();}
        if(!Arrays.equals(expected,Files.readAllBytes(directory.resolve("codex-petclinic-tests.json"))))
            throw new IllegalArgumentException("Canned edit manifest differs");
        for(var entry:CoverageEvidence.JSON.readTree(expected).required("files")) {
            String relative=entry.required("path").asText();Path file=directory.resolve("codex-petclinic-tests").resolve(relative);
            if(!file.toRealPath().equals(file.toAbsolutePath().normalize()) || !Evidence.sha256(file).equals(entry.required("sha256").asText()))
                throw new IllegalArgumentException("Canned test edit differs: "+relative);
        }
    }

    static Path originalManifest(Path evidence) throws Exception {
        var ref=CoverageEvidence.JSON.readValue(evidence.resolve("codex-original-acquisition-reference.json").toFile(),
                io.github.markpollack.experiment.runtime.AgentRecords.FileRef.class);
        Evidence.verify(ref);return Path.of(ref.path());
    }

    /**
     * V3 keeps its own reference to the provider's original sealed manifest, then hands the
     * sealed generation to Client's Journal recorder, which qualifies it with Journal's
     * qualifier for the acquisition profile, binds the qualified sources and delivers one
     * binding. V3 neither qualifies nor assembles the binding itself.
     */
    private static final class Recorder implements ExecutionRecorder {
        private final Path evidence;
        private final JournalExecutionRecorder journal;
        Recorder(Path evidence) throws Exception {
            this.evidence=evidence;
            Files.createDirectories(evidence.resolve("journal"));
            this.journal=new JournalExecutionRecorder(evidence.resolve("journal"),"v3-codex");
        }
        @Override public boolean takesNativeGeneration() { return true; }
        @Override public Supplier<String> prepare(ExecutionDecision decision) { throw new IllegalStateException("Original Codex acquisition required"); }
        /** Called once per decision; Client may call the returned delivery again on a retryable failure. */
        @Override public Prepared prepareRecording(ExecutionDecision decision, Optional<NativeGeneration> generation) {
            try {
                if(generation.isPresent())
                    CoverageEvidence.write(evidence.resolve("codex-original-acquisition-reference.json"),
                            Evidence.reference(generation.get().manifest(),"original-codex-acquisition"));
            } catch(Exception failure) {
                retain(failure);
                throw new IllegalStateException("Codex original acquisition reference failed: "+failure,failure);
            }
            var prepared=journal.prepareRecording(decision,generation);
            return new Prepared(() -> {
                try {return prepared.delivery().get();}
                catch(RuntimeException failure) {retain(failure);throw failure;}
            },prepared.nativeRecording());
        }
        private void retain(Exception failure) {
            try {var diagnostic=new java.io.StringWriter();failure.printStackTrace(new java.io.PrintWriter(diagnostic));
                Files.writeString(evidence.resolve("codex-archive-failure.txt"),diagnostic.toString(),StandardOpenOption.CREATE_NEW);}
            catch(Exception custodyFailure) {failure.addSuppressed(custodyFailure);}
        }
    }
}
