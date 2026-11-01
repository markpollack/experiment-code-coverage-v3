package io.tuvium.experiment.coverage;

import io.github.markpollack.experiment.runtime.AgentRecords.*;
import io.github.markpollack.experiment.runtime.Evidence;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Select repaired evidence for the same PetClinic attempt; callers save to a NEW shared store. */
public final class PilotRevision {
    private PilotRevision() {}

    public static Result select(Result original, Result assessment, Invocation repaired,
            List<FileRef> revisionEvidence, long createdAtEpochMs) throws IOException {
        if(original.items().size()!=1 || assessment.items().size()!=1)
            throw new IllegalArgumentException("One pilot item required");
        Row prior=original.items().getFirst(), selected=assessment.items().getFirst();
        if(!original.experimentId().equals(assessment.experimentId()) ||
                !original.experimentName().equals(assessment.experimentName()) ||
                !prior.identity().equals(selected.identity()) ||
                !prior.domain().equals(selected.domain()) ||
                !prior.workflowState().equals(selected.workflowState()) ||
                !prior.workflowProof().equals(selected.workflowProof()) ||
                !prior.workspaceState().equals(selected.workspaceState()) ||
                !prior.invocations().equals(selected.invocations()))
            throw new IllegalArgumentException("Assessment changed original task/domain/Workflow association");
        if(prior.invocations().size()!=1 || !selected.judgeState().equals("RETURNED"))
            throw new IllegalArgumentException("Retained task and returned Jury required");
        Invocation task=prior.invocations().getFirst();
        Invocation expected=new Invocation(task.attempt(),task.responseState(),task.processState(),task.exitCodes(),
                task.captureState(),task.acquisitions(),repaired.canonical(),"ASSEMBLED",task.providerSessions(),
                task.usage(),task.responseText(),task.error(),task.providerBudgetMs(),task.elapsedMs(),repaired.qualifications());
        if(!expected.equals(repaired) || repaired.qualifications().size()!=1 ||
                !repaired.qualifications().getFirst().state().equals("QUALIFIED"))
            throw new IllegalArgumentException("Repaired evidence changed immutable task facts");
        Evidence.verify(prior);Evidence.verify(selected);
        Row row=new Row(prior.identity(),List.of(repaired),prior.workflowState(),prior.domain(),selected.verdicts(),
                selected.judgeState(),selected.error(),selected.workspaceState(),prior.workflowProof());
        Evidence.verify(row);
        // Old stores and referenced derivatives remain explicit provenance, never overwritten.
        var refs=new LinkedHashSet<FileRef>(original.runtimeArtifacts());refs.addAll(assessment.runtimeArtifacts());
        for(var canonical:task.canonical()) refs.addAll(canonical.retainedFiles());
        for(var qualification:task.qualifications()) refs.addAll(qualification.retainedFiles());
        if(revisionEvidence.stream().noneMatch(r->r.subject().equals("parent-experiment-result")) ||
                revisionEvidence.stream().noneMatch(r->r.subject().equals("selected-assessment-result")))
            throw new IllegalArgumentException("Both immutable parent result references required");
        for(FileRef ref:revisionEvidence) {
            Evidence.verify(ref);
            Result expectedParent=ref.subject().equals("parent-experiment-result")?original:
                    ref.subject().equals("selected-assessment-result")?assessment:null;
            if(expectedParent!=null && !CoverageEvidence.JSON.readValue(Path.of(ref.path()).toFile(),Result.class).equals(expectedParent))
                throw new IllegalArgumentException("Revision parent reference does not match selected result");
            refs.add(ref);
        }
        return new Result(original.recordingSchemaVersion(),original.kind(),original.experimentId(),
                original.experimentName(),createdAtEpochMs,List.of(row),List.copyOf(refs));
    }
}
