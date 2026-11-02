package io.tuvium.experiment.coverage;

import io.github.markpollack.agents.claude.ClaudeClientProvider;
import io.github.markpollack.agents.client.*;
import io.github.markpollack.agents.client.journal.JournalExecutionRecorder;
import io.github.markpollack.claude.agent.sdk.hooks.HookCallback;
import io.github.markpollack.claude.agent.sdk.types.control.HookInput;
import io.github.markpollack.claude.agent.sdk.types.control.HookOutput;
import io.github.markpollack.experiment.runtime.Evidence;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Concrete bounded Client configuration; Client and SDK own execution and process lifetime. */
public final class NativeCoverageClient {
    public static final String CLI_VERSION = "2.1.316";
    public static final String MODEL = "claude-sonnet-4-6";
    public static final double MAX_BUDGET_USD = 5.0;
    public static final Duration SUBJECT_LIMIT = Duration.ofSeconds(900);
    public static final Duration CLEANUP_LIMIT = Duration.ofSeconds(15);
    public static final Duration OUTER_LIMIT = Duration.ofSeconds(1800);
    public static final List<String> TOOLS = List.of("Read", "Glob", "Grep", "Write", "Edit", "Bash");
    private NativeCoverageClient() {}

    public static AgentClient open(Path workspace, Path evidence, String executable) throws Exception {
        requireMavenEnvironment(System.getProperty("coverage.mavenRepo"), System.getenv("MAVEN_ARGS"));
        return configured(workspace, evidence, executable,
                JournalExecutionRecorder.claude(evidence.resolve("journal"), "v3-native-coverage"));
    }

    /** Maven launched by the permitted Bash action inherits this task-local repository. */
    static Path requireMavenEnvironment(String repository, String mavenArgs) throws Exception {
        if (repository == null || repository.isBlank()) throw new IllegalArgumentException("coverage.mavenRepo is required");
        Path path = Path.of(repository).toRealPath();
        if (!Files.isDirectory(path) || path.toString().chars().anyMatch(Character::isWhitespace)
                || !("-Dmaven.repo.local=" + path).equals(mavenArgs))
            throw new IllegalArgumentException("MAVEN_ARGS must select exactly the task-local coverage.mavenRepo");
        return path;
    }

    static AgentClient configured(Path workspace, Path evidence, String executable, ExecutionRecorder recorder)
            throws Exception {
        var hook = new RequiredPolicy(new CoverageEditPolicy(workspace), evidence.resolve("tool-policy.jsonl"));
        var provider = ClaudeClientProvider.builder().workingDirectory(workspace).claudePath(executable)
                .tools(TOOLS).maxBudgetUsd(MAX_BUDGET_USD).preToolUse(".*", hook)
                .acquisitionDirectory(evidence.resolve("sources"), CLI_VERSION).build();
        return AgentClient.builder(provider)
                .defaultOptions(DefaultAgentOptions.builder().model(MODEL).build())
                .defaultTimeout(SUBJECT_LIMIT).cleanupTimeout(CLEANUP_LIMIT)
                .executionRecorder(recorder).build();
    }

    /** Required synchronous tool permission; logging failure also denies the requested action. */
    static final class RequiredPolicy implements HookCallback {
        private final CoverageEditPolicy policy;
        private final Path log;
        RequiredPolicy(CoverageEditPolicy policy, Path log) throws Exception {
            this.policy = policy; this.log = log;
            Files.createFile(log);
        }
        @Override public synchronized HookOutput handle(HookInput input) {
            CoverageEditPolicy.Decision decision = new CoverageEditPolicy.Decision(false, "Unsupported hook input");
            String tool = "unknown", toolId = "unknown";
            try {
                if (input instanceof HookInput.PreToolUseInput request) {
                    tool = request.toolName(); toolId = request.toolUseId();
                    if (request.cwd() != null && Path.of(request.cwd()).toRealPath().equals(policy.workspace()))
                        decision = policy.beforeTool(tool, request.toolInput());
                    else decision = new CoverageEditPolicy.Decision(false, "Hook cwd differs from subject workspace");
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("tool", tool); row.put("toolUseId", toolId); row.put("allowed", decision.allowed());
                row.put("reason", decision.reason());
                row.put("inputSha256", Evidence.sha256(CoverageEvidence.JSON.writeValueAsBytes(input)));
                Files.writeString(log, CoverageEvidence.JSON.writeValueAsString(row) + "\n", StandardOpenOption.APPEND);
            } catch (Exception failure) {
                decision = new CoverageEditPolicy.Decision(false, "Required policy evaluation/evidence failed");
            }
            return HookOutput.builder().hookSpecificOutput(decision.allowed()
                    ? HookOutput.HookSpecificOutput.preToolUseAllow(decision.reason())
                    : HookOutput.HookSpecificOutput.preToolUseDeny(decision.reason())).build();
        }
    }
}
