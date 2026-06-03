package org.openwcs.slotting.velocity;

import java.util.Set;

/**
 * Transaction-log {@code eventType} values the velocity learner counts as outbound movement
 * (build.md §5.2, §9). Only pick/outbound events contribute to a SKU's velocity score; every
 * other event on {@code txlog.stream} is ignored (but still advances the cursor).
 */
public final class VelocityEventTypes {

    /** Outbound pick: stock decremented from a source bucket (mirrors inventory's {@code Picked}). */
    public static final String PICKED = "Picked";

    /** Pick-confirmed leg some producers emit instead of/in addition to {@code Picked}. */
    public static final String PICK_CONFIRMED = "PickConfirmed";

    /** Outbound shipment leg (goods leaving the warehouse). */
    public static final String OUTBOUND_SHIPPED = "OutboundShipped";

    /** Events that count as a pick/outbound movement for velocity. */
    public static final Set<String> OUTBOUND_EVENTS = Set.of(PICKED, PICK_CONFIRMED, OUTBOUND_SHIPPED);

    private VelocityEventTypes() {
    }
}
