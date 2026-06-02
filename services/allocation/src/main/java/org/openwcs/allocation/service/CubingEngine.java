package org.openwcs.allocation.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.openwcs.allocation.client.MasterDataClient.ShipperDef;
import org.openwcs.allocation.domain.ShipperAssignment;

/**
 * Greedy volumetric + weight cubing (ADR 0002). Packs order items unit-by-unit into a
 * shipper until usable volume (innerVolume × maxFillLevel) or net weight (maxWeight −
 * tare) is reached, then opens the next. Missing dimensions are treated as unconstrained
 * (0 item volume / no shipper volume limit). This is volume+weight cubing, not true 3D
 * bin-packing.
 */
public final class CubingEngine {

    private CubingEngine() {
    }

    /** An order item to cube: a SKU, a whole quantity, and per-unit volume/weight (base UoM). */
    public record Item(UUID skuId, long qty, double unitVolumeMm3, double unitWeightG) {
    }

    public static List<ShipperAssignment> appCube(List<Item> items, ShipperDef shipper) {
        double usableVolume = innerVolume(shipper) * fillLevel(shipper);
        double tare = shipper.tareWeightG() == null ? 0d : shipper.tareWeightG().doubleValue();
        double maxNet = (shipper.maxWeightG() == null ? Double.MAX_VALUE : shipper.maxWeightG().doubleValue()) - tare;

        List<ShipperAssignment> assignments = new ArrayList<>();
        Accumulator current = new Accumulator();
        for (Item item : items) {
            if (item.unitVolumeMm3() > usableVolume || item.unitWeightG() > maxNet) {
                throw new CannotCubeException(
                        "SKU " + item.skuId() + " does not fit shipper " + shipper.code());
            }
            for (long u = 0; u < item.qty(); u++) {
                if (!current.isEmpty()
                        && (current.volume + item.unitVolumeMm3() > usableVolume
                        || current.weight + item.unitWeightG() > maxNet)) {
                    assignments.add(current.close(assignments.size() + 1, shipper, tare));
                    current = new Accumulator();
                }
                current.add(item.skuId(), item.unitVolumeMm3(), item.unitWeightG());
            }
        }
        if (!current.isEmpty()) {
            assignments.add(current.close(assignments.size() + 1, shipper, tare));
        }
        return assignments;
    }

    private static double innerVolume(ShipperDef s) {
        if (s.lengthMm() == null || s.widthMm() == null || s.heightMm() == null) {
            return Double.MAX_VALUE;
        }
        return s.lengthMm().doubleValue() * s.widthMm().doubleValue() * s.heightMm().doubleValue();
    }

    private static double fillLevel(ShipperDef s) {
        return s.maxFillLevel() == null ? 1d : s.maxFillLevel().doubleValue();
    }

    private static final class Accumulator {
        private double volume;
        private double weight;
        private final Map<UUID, Long> contents = new LinkedHashMap<>();

        boolean isEmpty() {
            return contents.isEmpty();
        }

        void add(UUID skuId, double unitVolume, double unitWeight) {
            this.volume += unitVolume;
            this.weight += unitWeight;
            this.contents.merge(skuId, 1L, Long::sum);
        }

        ShipperAssignment close(int seqNo, ShipperDef shipper, double tare) {
            List<ShipperAssignment.Content> lines = new ArrayList<>();
            contents.forEach((sku, qty) -> lines.add(new ShipperAssignment.Content(sku, BigDecimal.valueOf(qty))));
            return new ShipperAssignment(
                    seqNo, shipper.id(), shipper.code(), lines,
                    BigDecimal.valueOf(tare + weight), BigDecimal.valueOf(volume));
        }
    }

    /** Thrown when an item cannot fit any available shipper. */
    public static class CannotCubeException extends RuntimeException {
        public CannotCubeException(String message) {
            super(message);
        }
    }
}
