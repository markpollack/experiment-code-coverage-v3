package io.tuvium.experiment.coverage;

import io.github.markpollack.experiment.runtime.*;
import io.github.markpollack.experiment.runtime.AgentRecords.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/**
 * Records written before Experiment runtime schema 5 keep their meaning. The fixtures under
 * {@code src/test/resources/pre-schema5} were written by Experiment 91817ec (schema 4); see
 * {@code docs/current-snapshots-2026-11-22.md} for their provenance. Their FileRefs are relative to the project root, which is the test working directory.
 */
class PreSchemaFiveTest {
    static final Path BASE = Path.of("src/test/resources/pre-schema5");
    static final Path PARENT = BASE.resolve("parent-store/cGlsb3Qtc2NoZW1hNA/run.json");
    static final Path ASSESSMENT = BASE.resolve("assessment-store/cGlsb3Qtc2NoZW1hNA/run.json");
    static final String PARENT_SHA256 = "d0819632c35f0d1b4726260cffd9b24743109e5cef305d21e526a94f0421ec80";
    static final String ASSESSMENT_SHA256 = "d1d0244a10c174349e9543e5ca361a32d3718c7e8588e443f8df144762fa9570";
    static final String INVOKED_SHA256 = "0600148e0647f21284afdce42c089862f0ee9be3def4012fad109fc486448043";
    @TempDir Path temp;

    @Test void revisionOfSchemaFourParentSavesAsCurrentSchemaAndLeavesParentUnchanged() throws Exception {
        assertThat(Evidence.sha256(PARENT)).isEqualTo(PARENT_SHA256);
        assertThat(Evidence.sha256(ASSESSMENT)).isEqualTo(ASSESSMENT_SHA256);
        var original = FileSystemResultStore.read(PARENT);
        var assessment = FileSystemResultStore.read(ASSESSMENT);
        assertThat(original.recordingSchemaVersion()).isEqualTo(4);
        var task = original.items().getFirst().invocations().getFirst();
        assertThat(task.attempt().execution()).isEqualTo(ExecutionKind.WORKFLOW);
        assertThat(original.items().getFirst().execution()).isEqualTo(RowExecution.WORKFLOW);

        var repaired = repair(task);
        var revision = PilotRevision.select(original, assessment, repaired, List.of(
                Evidence.reference(PARENT, "parent-experiment-result"),
                Evidence.reference(ASSESSMENT, "selected-assessment-result")), 300);
        assertThat(revision.recordingSchemaVersion()).isEqualTo(AgentRecords.CURRENT_SCHEMA_VERSION);
        var row = revision.items().getFirst();
        assertThat(row.execution()).isEqualTo(RowExecution.WORKFLOW);
        assertThat(row.executionState()).isEqualTo("SUCCEEDED");
        assertThat(row.workflowProof()).isEqualTo(original.items().getFirst().workflowProof());
        assertThat(row.invocations()).containsExactly(repaired);
        assertThat(row.verdicts()).isEqualTo(assessment.items().getFirst().verdicts());

        var store = new FileSystemResultStore(temp.resolve("revision-store"));
        store.save(revision);
        var readBack = new FileSystemResultStore(temp.resolve("revision-store")).load(revision.experimentId()).orElseThrow();
        assertThat(readBack).isEqualTo(revision);
        Evidence.verify(readBack.items().getFirst());
        assertThat(Evidence.sha256(PARENT)).isEqualTo(PARENT_SHA256);
        assertThat(Evidence.sha256(ASSESSMENT)).isEqualTo(ASSESSMENT_SHA256);
        assertThat(FileSystemResultStore.read(PARENT).recordingSchemaVersion()).isEqualTo(4);

        // A parent reference that does not match the selected parent is still refused.
        assertThatThrownBy(() -> PilotRevision.select(original, assessment, repaired, List.of(
                Evidence.reference(ASSESSMENT, "parent-experiment-result"),
                Evidence.reference(ASSESSMENT, "selected-assessment-result")), 300))
                .hasMessageContaining("does not match");
    }

    @Test void preSchemaFiveInvokedResumesAsWorkflowInvocationWithOriginalCoordinates() throws Exception {
        Path file = BASE.resolve("invoked.json");
        assertThat(Evidence.sha256(file)).isEqualTo(INVOKED_SHA256);
        var stored = CoverageEvidence.JSON.readTree(file.toFile()).path("invocation").path("attempt");
        assertThat(stored.has("execution")).isFalse();

        var invoked = NativeCoverageApp.readInvoked(file);
        var attempt = invoked.invocation().attempt();
        assertThat(attempt.execution()).isEqualTo(ExecutionKind.WORKFLOW);
        assertThat(attempt.invocationId()).isEqualTo(stored.path("invocationId").asText());
        assertThat(attempt.attemptNumber()).isEqualTo(stored.path("attemptNumber").asInt());
        assertThat(attempt.workflowCoordinates()).isEqualTo(new WorkflowCoordinates(stored.path("workflowRunId").asText(),
                stored.path("workflowInvocationId").asText(), stored.path("workflowAttemptId").asText()));
        assertThat(attempt.row()).isEqualTo(invoked.prepared().item().identity());
        assertThat(Evidence.sha256(file)).isEqualTo(INVOKED_SHA256);

        // A current file reads unchanged; an attempt shape that is neither is refused, not guessed.
        Path current = temp.resolve("current-invoked.json");
        CoverageEvidence.write(current, invoked);
        assertThat(NativeCoverageApp.readInvoked(current)).isEqualTo(invoked);
        var unknown = (com.fasterxml.jackson.databind.node.ObjectNode) CoverageEvidence.JSON.readTree(file.toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode) unknown.path("invocation").path("attempt")).remove("workflowAttemptId");
        Path partial = temp.resolve("partial-invoked.json");
        CoverageEvidence.write(partial, unknown);
        assertThatThrownBy(() -> NativeCoverageApp.readInvoked(partial)).hasMessageContaining("Unrecognized retained attempt shape");
    }

    /** The later repair: Journal-qualified derivatives and a canonical package for the same attempt. */
    private Invocation repair(Invocation task) throws Exception {
        FileRef original = task.acquisitions().getFirst();
        Path qualified = Files.createDirectory(temp.resolve("qualified"));
        var manifest = (com.fasterxml.jackson.databind.node.ObjectNode) CoverageEvidence.JSON.readTree(Path.of(original.path()).toFile());
        manifest.putObject("qualification").put("original_manifest_sha256", original.sha256());
        CoverageEvidence.write(qualified.resolve("acquisition.json"), manifest);
        Files.copy(Path.of(original.path()), qualified.resolve("client-acquisition.json"));
        CoverageEvidence.write(qualified.resolve("assembly-expectation.json"),
                Map.of("kind", "agent-journal/assembly-expectation", "version", "0.1"));
        var qualifiedManifest = Evidence.reference(qualified.resolve("acquisition.json"), "qualified-acquisition-manifest");
        var expectation = Evidence.reference(qualified.resolve("assembly-expectation.json"), "assembly-expectation");
        var copy = Evidence.reference(qualified.resolve("client-acquisition.json"), "qualified-source-file");
        var qualification = new Qualification(original, "QUALIFIED", qualified.toString(), List.of(qualifiedManifest),
                List.of(expectation), List.of(qualifiedManifest, copy, expectation), "synthetic-qualifier", "");
        Path canonical = Files.createDirectory(temp.resolve("canonical"));
        Files.writeString(canonical.resolve("events.jsonl"), "{\"synthetic\":\"repair fixture; not a Journal package\"}\n");
        var events = Evidence.reference(canonical.resolve("events.jsonl"), "canonical-events");
        var ref = new CanonicalRef(events, List.of(events), "synthetic-profile", "synthetic-writer", "COMPLETED", "NOT_SOURCE_VERIFIED");
        return new Invocation(task.attempt(), task.responseState(), task.processState(), task.exitCodes(), task.captureState(),
                task.acquisitions(), List.of(ref), "ASSEMBLED", task.providerSessions(), task.usage(), task.responseText(),
                task.error(), task.providerBudgetMs(), task.elapsedMs(), List.of(qualification));
    }
}
