package org.openwcs.slotting.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A request to place an inbound handling unit. {@code blockId} is optional — when omitted the
 * engine resolves the block from the SKU's storage profile. {@code uomId} narrows direct-to-pick
 * to a matching pick face. {@code huType} (the handling-unit type name) is checked against the
 * block's and each location's allowed-HU-types when present. {@code empty} marks an empty-HU
 * put-away (no SKU): the carrier is stored far from the exit and moved at lower priority.
 *
 * <p>{@code compartments} describes a multi-compartment HU (1–8, each a different SKU). When given,
 * the <b>dominant</b> compartment (most qty) drives velocity placement and the full compartment
 * SKU set drives lane affinity. A single-SKU HU can just set {@code skuId} and leave compartments
 * empty (equivalent to one compartment).
 */
public record PutawayRequest(
        UUID warehouseId,
        UUID huId,
        UUID skuId,
        UUID batchId,
        UUID uomId,
        BigDecimal qty,
        UUID blockId,
        String huType,
        boolean empty,
        List<Compartment> compartments) {

    /** One compartment of a handling unit: a SKU and its quantity (used to pick the dominant). */
    public record Compartment(UUID skuId, BigDecimal qty) {
    }
}
