package org.openwcs.integration.host.api;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Canonical outbound order from a host system. Vendor-neutral: SAP/Manhattan adapters translate
 * their formats into this. Maps to an order-management OUTBOUND order.
 */
public record CreateHostOrder(
        @NotNull String orderRef,
        @NotNull UUID warehouseId,
        String customerRef,
        Integer priority,
        String dispatchBy,
        String serviceCode,
        String routeCode,
        ShipTo shipTo,
        String labelTemplateCode,
        @NotEmpty List<Line> lines) {

    public record Line(@NotNull UUID skuId, @NotNull BigDecimal qty) {
    }

    public record ShipTo(String name, String line1, String line2, String city, String region,
                         String postcode, String country, String contact, String phone) {
    }
}
