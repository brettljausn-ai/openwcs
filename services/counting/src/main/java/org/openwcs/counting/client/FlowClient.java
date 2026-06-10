package org.openwcs.counting.client;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Write seam onto flow-orchestrator. Flow owns the inbound induction queue (ADR-0007 Phase 3c-1):
 * counting requests presentation of a count tote at a workplace and flow orchestrates the
 * retrieve + convey journey itself.
 */
public interface FlowClient {

    /**
     * Request presentation of an HU at a workplace (ADR-0007 §3.1). Flow creates a REQUESTED
     * induction entry and orchestrates the RETRIEVE + CONVEY journey itself; counting no longer
     * dispatches the retrieval. Returns the created induction entry id.
     */
    UUID requestPresentation(InductionRequest request);

    /**
     * Create a transport device task. Returns the created task id, or {@code null} if the call did
     * not yield one.
     *
     * @deprecated ADR-0007 Phase 3c-1: counting no longer dispatches the RETRIEVE itself — flow
     *     orchestrates retrieve + convey from {@link #requestPresentation(InductionRequest)}. Kept
     *     for any remaining callers; unused by the count-tote routing path.
     */
    @Deprecated
    UUID createTransport(UUID warehouseId, String family, String command, Map<String, Object> payload,
                         UUID correlationId);

    /**
     * Body for {@code POST /api/flow/induction/requests} (ADR-0007 §3.1). Flow meters the cap and
     * orchestrates the journey; {@code REQUESTED} is uncapped so the request always succeeds.
     */
    record InductionRequest(
            UUID warehouseId,
            UUID workplaceId,
            String workplaceKind,
            UUID huId,
            String huCode,
            UUID skuId,
            String skuCode,
            BigDecimal qty,
            UUID locationId,
            String mode,
            String family,
            UUID countTaskId,
            UUID countLineId) {
    }
}
