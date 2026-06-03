package org.openwcs.integration.host.api;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Canonical advance ship notice (expected receipt) from a host system. Maps to an
 * order-management INBOUND order.
 */
public record CreateAsn(
        @NotNull String asnRef,
        @NotNull UUID warehouseId,
        String supplierRef,
        @NotEmpty List<Line> lines) {

    public record Line(@NotNull UUID skuId, @NotNull BigDecimal qty) {
    }
}
