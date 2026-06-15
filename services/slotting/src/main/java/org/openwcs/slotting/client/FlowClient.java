package org.openwcs.slotting.client;

import java.util.UUID;

/**
 * Dispatches a slotting plan as a physical move to flow-orchestrator (the execution layer).
 * Slotting plans (re-slot recommendations, replenishment tasks, decant put-aways) describe a HU
 * that must travel from one location to another; flow turns that into a real device task on the
 * resolved transport family and returns its id.
 *
 * <p>Best-effort by contract: a flow hiccup must never corrupt slotting's plan state. Callers keep
 * the plan un-dispatched and surface the error rather than flipping a status when the move could not
 * be created (see {@link FlowDispatchException}).
 */
public interface FlowClient {

    /**
     * Ask flow to physically move {@code huId} from {@code fromLocationId} to {@code toLocationId}.
     * {@code fromLocationId} may be null/blank when the source is not resolvable (flow then picks
     * up the HU wherever the registry says it currently is). Returns the created device-task id.
     *
     * @throws FlowDispatchException when flow is unreachable, times out, or returns no task id
     */
    UUID dispatchMove(UUID warehouseId, UUID huId, String huCode, UUID fromLocationId,
                      UUID toLocationId, String family, String reason);

    /** Thrown when a move could not be created in flow; the plan must stay un-dispatched. */
    class FlowDispatchException extends RuntimeException {
        public FlowDispatchException(String message, Throwable cause) {
            super(message, cause);
        }

        public FlowDispatchException(String message) {
            super(message);
        }
    }
}
