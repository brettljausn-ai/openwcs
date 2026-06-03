package org.openwcs.gtp.api;

import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * Confirm a put instruction. {@code qty} is optional — omitted means the full lit quantity was
 * put; a smaller value is a short put (the instruction closes SHORT and the destination's
 * remaining demand stays open for a later cycle).
 */
public record ConfirmPutRequest(
        @Positive BigDecimal qty) {
}
