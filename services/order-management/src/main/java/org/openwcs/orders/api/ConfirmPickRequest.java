package org.openwcs.orders.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Confirm an operator pick against a pick-task line. {@code pickedQty} is the quantity the
 * operator actually picked (positive; zero is allowed only for a short confirm that records
 * nothing could be picked). {@code short} = true marks the line SHORT when the operator
 * cannot fully satisfy the remaining quantity (e.g. the bin was empty). {@code locationId}
 * is the bin the operator picked from, recorded on the Picked transaction; when omitted the
 * last known pick location is reused (see {@link PickTaskView} for the location gap).
 * {@code processInstanceId} is the handheld process run that drove this pick (optional, for
 * traceability); when omitted in the body the {@code X-Process-Instance} header is used.
 */
public record ConfirmPickRequest(
        @NotNull @PositiveOrZero BigDecimal pickedQty,
        @JsonProperty("short") Boolean shortPick,
        UUID locationId,
        String processInstanceId) {

    /** True when the operator flagged this as a short pick. */
    public boolean isShort() {
        return Boolean.TRUE.equals(shortPick);
    }
}
