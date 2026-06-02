package org.openwcs.allocation.api;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/** Request to build pick batches from a set of already-allocated orders. */
public record BuildBatchesRequest(
        @NotNull UUID warehouseId,
        @NotEmpty List<String> orderRefs) {
}
