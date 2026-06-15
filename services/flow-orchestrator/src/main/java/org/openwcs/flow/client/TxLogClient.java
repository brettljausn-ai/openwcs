package org.openwcs.flow.client;

import java.util.Map;
import java.util.UUID;

/**
 * Appends append-only events to the txlog system-of-record ({@code POST /api/txlog/events}).
 * Flow uses this to record physical handling-unit moves ({@code HandlingUnitMoved}) as a durable,
 * replayable audit trail alongside the per-HU transport trace (which is flow-local).
 */
public interface TxLogClient {

    /** {@code eventType} for a completed/failed physical move of a handling unit. Audit-only:
     *  the inventory stock projection deliberately does not consume it (see the note on
     *  inventory's {@code STOCK_EVENTS}). */
    String HANDLING_UNIT_MOVED = "HandlingUnitMoved";

    /**
     * Append one event. Returns the assigned event id, or {@code null} when the append could not be
     * confirmed. Implementations are best-effort: callers run this as an audit side effect and must
     * never let a txlog hiccup fail the surrounding device-task transition.
     */
    UUID append(String streamId, String eventType, UUID correlationId, String actor, Map<String, Object> payload);
}
