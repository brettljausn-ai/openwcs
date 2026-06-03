package org.openwcs.slotting.api;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/** The engine's chosen destination for an inbound HU. */
public record PutawayDecision(
        UUID assignmentId,
        UUID locationId,
        UUID blockId,
        String mode,            // RESERVE | DIRECT_TO_PICK
        BigDecimal score,
        Map<String, Object> factors) {
}
