package org.openwcs.allocation.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * One order in a pick batch: its position in the tote and the final shipper(s) its items
 * are separated into at packing. Persisted as JSONB on {@code pick_batch.members}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BatchMember(
        String orderRef,
        int totePosition,
        List<ShipperAssignment> finalShippers) {
}
