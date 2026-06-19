package org.openwcs.processdesigner.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;
import org.openwcs.processdesigner.domain.ProcessStepEvent;

/**
 * One ordered entry in an instance's step history, returned by {@code GET /instances/{id}/steps} for
 * exact replay: the seq, the step it advanced to (and its type), the merged data object at that
 * point, who entered it, and when.
 */
public record StepEventView(
        UUID id,
        long seq,
        String stepId,
        String stepType,
        JsonNode data,
        String enteredBy,
        Instant at) {

    public static StepEventView from(ProcessStepEvent e) {
        return new StepEventView(e.getId(), e.getSeq(), e.getStepId(), e.getStepType(), e.getData(),
                e.getEnteredBy(), e.getAt());
    }
}
