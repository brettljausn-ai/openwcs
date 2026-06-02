package org.openwcs.allocation.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A combined pick across all orders in a batch: total quantity of one SKU at one
 * location, with the underlying per-order reservation ids. Persisted as JSONB on
 * {@code pick_batch.pick_lines}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MergedPickLine(
        UUID locationId,
        UUID skuId,
        BigDecimal qty,
        List<UUID> reservationIds) {
}
