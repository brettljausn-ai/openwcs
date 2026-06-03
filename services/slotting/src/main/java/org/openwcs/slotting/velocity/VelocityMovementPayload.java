package org.openwcs.slotting.velocity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Pick/outbound payload the velocity learner reads off the transaction-log envelope. Mirrors
 * the shape of {@code inventory}'s {@code BucketQty}/move payloads — only the fields the learner
 * needs ({@code warehouseId}, {@code skuId}, and an optional {@code qty}) are mapped. Unknown
 * JSON fields are ignored so payloads can evolve without breaking this consumer.
 *
 * <p>An outbound event counts as one pick occurrence regardless of {@code qty} (velocity is a
 * recency-weighted frequency, not a volume), so {@code qty} is accepted but not required.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VelocityMovementPayload(
        UUID warehouseId,
        UUID skuId,
        BigDecimal qty) {
}
