package io.tuvium.experiment.coverage;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.markpollack.experiment.runtime.AgentRecords;
import io.github.markpollack.experiment.runtime.AgentRecords.*;
import io.github.markpollack.experiment.runtime.Evidence;
import io.github.markpollack.experiment.runtime.FileSystemResultStore;
import io.tuvium.experiment.coverage.CoverageAgentInvoker.*;
import java.nio.file.*;
import java.util.*;

/**
 * Provenance only; not compiled by the build. Writes the pre-schema-5 fixtures in this directory
 * with Experiment runtime 91817ec (schema 4) on the classpath in place of the current runtime, and
 * V3's own CoverageEvidence writer. Run once from the V3 project root; every FileRef is relative
 * to that root. See README.md for the exact command and the resulting hashes.
 */
public final class GeneratePreSchema5 {
    static final Path BASE = Path.of("src/test/resources/pre-schema5");

    public static void main(String[] args) throws Exception {
        if (AgentRecords.CURRENT_SCHEMA_VERSION != 4) throw new IllegalStateException("Run with the schema 4 writer");
        var identity = Identity.of("pilot-schema4", "spring-petclinic-partial", "simple", 0);
        var attempt = new Attempt(identity, "7d0f5a52-6b1e-4f43-9a4c-2f0b9c1d6e01",
                "3c8e2a90-1f5d-4b7e-8c2a-5e6f7a8b9c01",
                "sha256:9f2c4e6a8b0d1f3e5a7c9e1b3d5f7a9c1e3b5d7f9a1c3e5b7d9f1a3c5e7b9d1f",
                "b4a6c8e0-2d4f-4a6c-8e0b-2d4f6a8c0e01", 1);
        Path evidence = BASE.resolve("evidence");
        Files.createDirectories(evidence.resolve("workflow"));
        Files.createDirectories(evidence.resolve("sources"));

        var snapshot = CoverageEvidence.JSON.createObjectNode().put("synthetic", "V3 pre-schema-5 fixture; not a Workflow run")
                .put("runId", attempt.workflowRunId()).put("status", "SUCCEEDED");
        var logical = snapshot.putArray("invocations").addObject().put("invocationId", attempt.workflowInvocationId());
        logical.putArray("attempts").addObject().put("attemptId", attempt.workflowAttemptId())
                .put("number", attempt.attemptNumber()).put("disposition", "COMMITTED");
        Path snapshotFile = evidence.resolve("workflow/snapshot.json");
        CoverageEvidence.write(snapshotFile, snapshot);

        var manifest = CoverageEvidence.JSON.createObjectNode().put("kind", "agent-journal/acquired-execution")
                .put("version", "0.1").put("synthetic", "V3 pre-schema-5 fixture; not a native capture")
                .put("complete", true).put("acquisition_uid", attempt.invocationId());
        var run = manifest.putObject("run").put("run_uid", attempt.invocationId());
        var provenance = run.putObject("launch_settings").putObject("artifact_provenance");
        new TreeMap<>(io.github.markpollack.experiment.runtime.AcquisitionAssembly.provenance(attempt))
                .forEach(provenance::put);
        Path manifestFile = evidence.resolve("sources/acquisition.json");
        CoverageEvidence.write(manifestFile, manifest);

        var acquisition = relative(manifestFile, "acquisition-manifest");
        var invocation = new Invocation(attempt, "SUCCEEDED", "EXITED", List.of(0), "COMPLETE", List.of(acquisition),
                List.of(), "REFUSED", List.of("synthetic-session"),
                List.of(Measurement.unknown("cost", "USD", "synthetic fixture", attempt.invocationId())),
                "synthetic response", "Original assembly refused (synthetic fixture)", 900000, 1000, List.of());
        var proof = new WorkflowProof(identity, attempt.workflowRunId(), "SUCCEEDED", "DRAINED",
                relative(snapshotFile, "workflow-snapshot"), List.of(attempt));
        var domain = new Domain(List.of(Measurement.measured("lineCoverage", "percent", 64.8649, "synthetic fixture", "item")), List.of());
        var parentRow = new Row(identity, List.of(invocation), "SUCCEEDED", domain, List.of(), "NOT_ASSESSED", "",
                "RETAINED", List.of(proof));
        new FileSystemResultStore(BASE.resolve("parent-store")).save(new Result(AgentRecords.CURRENT_SCHEMA_VERSION,
                "AGENT_CANONICAL", identity.runId(), "coverage-v3-pilot", 100, List.of(parentRow), List.of()));

        // Experiment 91817ec's own schema 4 fixture verdict, unchanged (experiment-runtime/src/test/resources/schema4/workflow-succeeded.json).
        var experimentFixture = (ObjectNode) CoverageEvidence.JSON.readTree(Path.of(args[0]).toFile());
        var verdict = CoverageEvidence.JSON.treeToValue(experimentFixture.path("items").path(0).path("verdicts").path(0), VerdictDocument.class);
        var assessedRow = new Row(identity, List.of(invocation), "SUCCEEDED", domain, List.of(verdict), "RETURNED", "",
                "RETAINED", List.of(proof));
        new FileSystemResultStore(BASE.resolve("assessment-store")).save(new Result(AgentRecords.CURRENT_SCHEMA_VERSION,
                "AGENT_CANONICAL", identity.runId(), "coverage-v3-pilot", 200, List.of(assessedRow), List.of()));

        var task = new Task(identity, "/synthetic/workspace", "synthetic prompt", "synthetic-model", "/synthetic/evidence");
        var item = new PreparedItem(identity, "/synthetic/workspace", domain.measurements(), List.of());
        CoverageEvidence.write(BASE.resolve("invoked.json"),
                new NativeCoverageApp.Invoked(new Prepared(task, item, "synthetic expanded prompt"), invocation));
    }

    static FileRef relative(Path file, String subject) throws Exception {
        return new FileRef(file.toString(), Evidence.sha256(file), subject);
    }
}
