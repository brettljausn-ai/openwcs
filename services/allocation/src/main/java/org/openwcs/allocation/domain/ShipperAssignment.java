package org.openwcs.allocation.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * One physical shipper (carton/tote) the order is cubed into, with its contents and computed
 * load. Persisted as JSONB in {@code order_allocation.shippers}.
 *
 * <p>Links (build.md §6, ADR 0002):
 * <ul>
 *   <li><b>shipper → order</b>: the owning {@code order_allocation} row ({@code order_ref}).
 *       {@code shipperUnitId} additionally gives each carton a stable identity within the
 *       order so it can be referenced downstream (manifests, device tasks).</li>
 *   <li><b>content → order line</b>: each {@link Content} carries the {@code lineNo} of the
 *       order line it fulfils, so a line split across several cartons (and a carton holding
 *       several lines) is fully traceable.</li>
 * </ul>
 * {@code shipperId}/{@code shipperCode} identify the shipper <em>type</em> (master-data); an
 * order may use several types of different sizes.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ShipperAssignment(
        UUID shipperUnitId,
        int seqNo,
        UUID shipperId,
        String shipperCode,
        List<Content> contents,
        BigDecimal grossWeightG,
        BigDecimal usedVolumeMm3) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Content(int lineNo, UUID skuId, BigDecimal qty) {
    }
}
