package org.openwcs.counting.api;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.openwcs.counting.service.CountTaskScope;
import org.openwcs.counting.service.CreateCountTaskCommand;

/** Request body to create an ad-hoc count task over a scope. */
public record CreateCountTaskRequest(
        @NotNull UUID warehouseId,
        String scopeType,
        UUID scopeRef,
        String countType,
        BigDecimal tolerance,
        UUID gtpStationId,
        @NotEmpty List<Cell> cells) {

    /** A (location, SKU[, batch]) cell to count. */
    public record Cell(@NotNull UUID locationId, @NotNull UUID skuId, UUID batchId, String uomCode) {
    }

    public CreateCountTaskCommand toCommand() {
        List<CountTaskScope> mapped = cells.stream()
                .map(c -> new CountTaskScope(c.locationId(), c.skuId(), c.batchId(), c.uomCode()))
                .toList();
        return new CreateCountTaskCommand(
                warehouseId, scopeType, scopeRef, countType, "AD_HOC", null, null,
                tolerance, gtpStationId, mapped);
    }
}
