package org.openwcs.allocation.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.openwcs.allocation.domain.MergedPickLine;

/**
 * Merges the picks of several orders into one combined pick list for batch picking
 * (ADR 0002 §6): same SKU at the same location becomes a single pick whose quantity is
 * the sum, retaining the per-order reservation ids for the separation step.
 */
public final class BatchPlanner {

    private BatchPlanner() {
    }

    /** A single order's pick before merging. */
    public record PickItem(UUID locationId, UUID skuId, BigDecimal qty, UUID reservationId) {
    }

    public static List<MergedPickLine> merge(List<PickItem> items) {
        Map<String, Agg> byBucket = new LinkedHashMap<>();
        for (PickItem item : items) {
            String key = item.locationId() + "|" + item.skuId();
            Agg agg = byBucket.computeIfAbsent(key, k -> new Agg(item.locationId(), item.skuId()));
            agg.qty = agg.qty.add(item.qty());
            if (item.reservationId() != null) {
                agg.reservationIds.add(item.reservationId());
            }
        }
        List<MergedPickLine> lines = new ArrayList<>();
        for (Agg agg : byBucket.values()) {
            lines.add(new MergedPickLine(agg.locationId, agg.skuId, agg.qty, agg.reservationIds));
        }
        return lines;
    }

    private static final class Agg {
        private final UUID locationId;
        private final UUID skuId;
        private BigDecimal qty = BigDecimal.ZERO;
        private final List<UUID> reservationIds = new ArrayList<>();

        private Agg(UUID locationId, UUID skuId) {
            this.locationId = locationId;
            this.skuId = skuId;
        }
    }
}
