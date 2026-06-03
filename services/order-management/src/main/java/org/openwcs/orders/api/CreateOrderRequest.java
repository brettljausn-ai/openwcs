package org.openwcs.orders.api;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request body to create an order (any type) with its lines. */
public record CreateOrderRequest(
        @NotNull String orderRef,
        @NotNull UUID warehouseId,
        /** INBOUND | OUTBOUND | COUNT | ADJUSTMENT; defaults to OUTBOUND. */
        String orderType,
        String customerRef,
        Integer priority,
        /** Required ship / cut-off time; drives release ordering with priority. */
        Instant dispatchBy,
        /** Dispatch service level (master-data shipping-service code), e.g. EXPRESS. */
        String serviceCode,
        /** Dispatch route (master-data route code, host-fed), e.g. CENTRAL_LONDON. */
        String routeCode,
        /** Ship-to address; used to populate dispatch labels. */
        org.openwcs.orders.domain.ShipToAddress shipTo,
        /** Override dispatch-label template (master-data label-template code). */
        String labelTemplateCode,
        @NotEmpty List<Line> lines) {

    public record Line(
            @NotNull UUID skuId,
            @NotNull @Positive BigDecimal qty) {
    }
}
