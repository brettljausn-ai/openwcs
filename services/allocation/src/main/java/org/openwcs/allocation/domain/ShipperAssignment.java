package org.openwcs.allocation.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * One physical shipper the order is cubed into, with its contents and computed load.
 * Persisted as JSONB on {@code order_allocation.shippers}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ShipperAssignment(
        int seqNo,
        UUID shipperId,
        String shipperCode,
        List<Content> contents,
        BigDecimal grossWeightG,
        BigDecimal usedVolumeMm3) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Content(UUID skuId, BigDecimal qty) {
    }
}
