package io.tuvium.experiment.coverage;

import io.github.markpollack.agents.client.*;
import io.github.markpollack.experiment.runtime.AsyncClientInvocationService;
import io.github.markpollack.experiment.runtime.AgentRecords.Attempt;
import java.nio.file.Path;
import java.util.*;

/** Nonwaiting observations of the original handle on successful and exceptional returns. */
final class NativeAttemptCustody {
    static void retain(Path file, Attempt attempt, AsyncClientInvocationService.StartedExecution started,
            ExecutionTermination termination, Exception failure) throws Exception {
        var row=new LinkedHashMap<String,Object>();
        row.put("attempt",attempt); row.put("handleAvailable",started!=null);
        row.put("failure",failure==null?"":failure.toString());
        row.put("terminationState",termination==null?"NOT_OBSERVED":"OBSERVED");
        row.put("termination",termination==null?null:termination(termination));
        if(started!=null) {
            var handle=started.execution();
            row.put("executionId",handle.executionId()); row.put("sessionId",handle.sessionId());
            try {
                var decision=handle.decision(); row.put("decision",decision.map(NativeAttemptCustody::decision).orElse(null));
                row.put("outcomeText",decision.map(d->d.outcome().toString()).orElse("NOT_ELECTED"));
                row.put("outcomeType",decision.map(d->d.outcome().getClass().getSimpleName()).orElse("NOT_ELECTED"));
            } catch(RuntimeException error) {row.put("decisionObservationError",error.toString());}
            try {row.put("recording",handle.recording().toString());}
            catch(RuntimeException error) {row.put("recordingObservationError",error.toString());}
            try {started.decision();row.put("associationObservation","MATCHED");}
            catch(RuntimeException error) {row.put("associationObservationError",error.toString());}
        }
        CoverageEvidence.write(file,row);
    }
    private static Map<String,Object> decision(ExecutionDecision actual) {
        var value=new LinkedHashMap<String,Object>();
        value.put("decisionId",actual.decisionId());value.put("executionId",actual.executionId());
        value.put("sessionId",actual.sessionId());value.put("connectionId",actual.connectionId().orElse(null));
        value.put("acceptedAt",actual.acceptedAt().toString());
        value.put("outcomeType",actual.outcome().getClass().getSimpleName());
        value.put("outcomeObservation",actual.outcome().toString());
        var provider=new LinkedHashMap<String,Object>();provider.put("kind",actual.provider().kind().name());
        provider.put("nativeReason",actual.provider().nativeReason().orElse(null));
        provider.put("observedExitCode",actual.provider().observedExitCode().isPresent()?actual.provider().observedExitCode().getAsInt():null);
        value.put("provider",provider);
        var c=actual.correlation();var correlation=new LinkedHashMap<String,Object>();
        correlation.put("experimentId",c.experimentId().orElse(null));
        correlation.put("experimentRunId",c.experimentRunId().orElse(null));
        correlation.put("experimentInvocationId",c.experimentInvocationId().orElse(null));
        correlation.put("workflowRunId",c.workflowRunId().orElse(null));
        correlation.put("workflowInvocationId",c.workflowInvocationId().orElse(null));
        correlation.put("workflowAttemptId",c.workflowAttemptId().orElse(null));value.put("correlation",correlation);
        return value;
    }

    private static Map<String,Object> termination(ExecutionTermination actual) {
        var value=new LinkedHashMap<String,Object>();
        value.put("executionId",actual.executionId());value.put("sessionId",actual.sessionId());
        value.put("observedAt",actual.observedAt().toString());value.put("settledAt",actual.settledAt().map(Object::toString).orElse(null));
        value.put("cleanupConfirmed",actual.cleanupConfirmed());value.put("unconfirmedResources",actual.unconfirmedResources());
        value.put("providerTurnDispatched",actual.providerTurnDispatched());value.put("reconnected",actual.reconnected());
        value.put("sessionState",actual.sessionState().name());value.put("stopReason",actual.stopReason().map(Object::toString).orElse(null));
        return value;
    }

}
