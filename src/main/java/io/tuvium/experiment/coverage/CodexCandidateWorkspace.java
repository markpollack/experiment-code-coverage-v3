package io.tuvium.experiment.coverage;

import io.github.markpollack.experiment.runtime.AgentRecords.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** First-run final-state acceptance, separate from Claude's preventive policy. */
final class CodexCandidateWorkspace {
    final Path project, subject, evidence, verification;
    CoverageEditPolicy verificationPolicy;
    CoverageEditPolicy.Snapshot verificationBefore;
    CodexCandidateWorkspace(Path project,Path subject,Path evidence) throws Exception {
        this.project=project;this.subject=subject;this.evidence=evidence;
        this.verification=evidence.resolve("verification");
        command(List.of("--subject",subject.toString(),"--before",evidence.resolve("candidate-before.json").toString()));
    }
    void command(List<String> arguments) throws Exception {
        var command=new ArrayList<String>(List.of(System.getProperty("coverage.candidatePython"),
                System.getProperty("coverage.candidateSurface")));
        command.addAll(arguments);
        Path log=Files.createTempFile(evidence,"candidate-surface-",".log");
        Process p=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if(!p.waitFor(90,TimeUnit.SECONDS)) {p.destroyForcibly();throw new IllegalStateException("Inventory verification timed out");}
        if(p.exitValue()!=0)throw new IllegalStateException("Candidate inventory refused; see "+log);
    }
    CoverageAgentInvoker.Prepared transfer(CoverageAgentInvoker.Prepared prepared) throws Exception {
        PinnedCoverageTask.prepare(project,verification);
        command(List.of("--subject",subject.toString(),"--before",evidence.resolve("candidate-before.json").toString(),
                "--output",evidence.resolve("candidate-patch.json").toString(),"--verification",verification.toString()));
        verificationPolicy=new CoverageEditPolicy(verification);verificationBefore=verificationPolicy.snapshot();
        var original=prepared.item();
        var item=new PreparedItem(original.identity(),verification.toString(),original.baseline(),original.artifacts());
        var task=prepared.task();
        return new CoverageAgentInvoker.Prepared(new CoverageAgentInvoker.Task(task.identity(),verification.toString(),task.prompt(),task.model(),task.evidenceRoot()),item,prepared.expandedPrompt());
    }
    void recheck() throws Exception {
        command(List.of("--subject",subject.toString(),"--before",evidence.resolve("candidate-before.json").toString(),
                "--output",evidence.resolve("candidate-final-recheck.json").toString()));
        // Full verification-tree inventory includes test bytes; tests cannot rewrite their own inputs.
        command(List.of("--subject",verification.toString(),"--before",evidence.resolve("verification-before-build.json").toString(),
                "--output",evidence.resolve("verification-after-build.json").toString()));
        var delta=CoverageEvidence.JSON.readTree(evidence.resolve("verification-after-build.json").toFile());
        if(!delta.required("changes").isEmpty())throw new IllegalStateException("Submitted build changed test inputs");
    }
    void snapshotVerification() throws Exception {
        command(List.of("--subject",verification.toString(),"--before",evidence.resolve("verification-before-build.json").toString()));
    }
}
