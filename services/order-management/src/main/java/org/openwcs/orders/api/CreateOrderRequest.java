package org.openwcs.orders.api;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request body to create an outbound order with its lines. */
public record CreateOrderRequest(
        @NotNull String orderRef,
        @NotNull UUID warehouseId,
        String customerRef,
        Integer priority,
        /** Required ship / cut-off time; drives release ordering with priority. */
        Instant dispatchBy,
        @NotEmpty List<Line> lines) {

    public record Line(
            @NotNull UUID skuId,
            @NotNull @Positive BigDecimal qty) {
    }
}
