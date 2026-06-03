package org.openwcs.allocation.service;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.openwcs.allocation.client.MasterDataClient.ShipperDef;
import org.openwcs.allocation.domain.ShipperAssignment;

/**
 * Greedy volumetric + weight cubing across multiple shipper sizes (ADR 0002). Packs an
 * order's units into the <b>largest</b> carton that fits while a lot remains, then downsizes
 * to the <b>smallest</b> carton that can hold the remainder — so a big order ships as, e.g., a
 * large carton followed by a smaller one for the leftover quantity. A single order line is
 * split across cartons as needed, and every unit keeps its order line number so each carton's
 * contents trace back to the line they fulfil.
 *
 * <p>Missing dimensions are treated as unconstrained (0 item volume / no shipper volume
 * limit). This is volume+weight cubing, not true 3D bin-packing.
 */
public final class CubingEngine {

    private CubingEngine() {
    }

    /** An order item to cube: its order line, SKU, whole quantity, and per-unit volume/weight (base UoM). */
    public record Item(int lineNo, UUID skuId, long qty, double unitVolumeMm3, double unitWeightG) {
    }

    /** A single unit queued for packing, tagged with the order line it belongs to. */
    private record Unit(int lineNo, UUID skuId, double volume, double weight) {
    }

    /** Usable capacity of a shipper type: usable volume (inner × fill level) and net weight (max − tare). */
    private record Capacity(ShipperDef shipper, double usableVolume, double maxNet, double tare) {
        boolean fits(double volume, double weight) {
            return volume <= usableVolume && weight <= maxNet;
        }
    }

    /**
     * Cube the items across the available shipper types, largest-first with downsizing.
     *
     * @param shippers candidate shipper types (e.g. the warehouse's active shippers); ordering
     *                 is irrelevant — they are ranked by usable capacity internally.
     */
    public static List<ShipperAssignment> appCube(List<Item> items, List<ShipperDef> shippers) {
        // Rank shippers by usable capacity, largest first.
        List<Capacity> byCapacity = shippers.stream()
                .map(CubingEngine::capacity)
                .sorted(Comparator.comparingDouble(Capacity::usableVolume)
                        .thenComparingDouble(Capacity::maxNet)
                        .thenComparing(c -> c.shipper().code())
                        .reversed())
                .toList();
        if (byCapacity.isEmpty()) {
            throw new CannotCubeException("No shipper available to cube the order");
        }
        Capacity largest = byCapacity.get(0);

        // Flatten items into a stable queue of units, preserving line order.
        Deque<Unit> units = new ArrayDeque<>();
        double remainingVolume = 0;
        double remainingWeight = 0;
        for (Item item : items) {
            if (!largest.fits(item.unitVolumeMm3(), item.unitWeightG())) {
                throw new ItemDoesNotFitException(item.lineNo(), item.skuId(), largest.shipper().code(),
                        "SKU " + item.skuId() + " (line " + item.lineNo() + ") does not fit the largest shipper "
                                + largest.shipper().code());
            }
            for (long u = 0; u < item.qty(); u++) {
                units.add(new Unit(item.lineNo(), item.skuId(), item.unitVolumeMm3(), item.unitWeightG()));
                remainingVolume += item.unitVolumeMm3();
                remainingWeight += item.unitWeightG();
            }
        }

        List<ShipperAssignment> assignments = new ArrayList<>();
        while (!units.isEmpty()) {
            // If a single (smaller) carton can hold everything that's left, use it — this makes
            // the final carton right-sized for the remainder.
            Capacity carton = smallestThatFits(byCapacity, remainingVolume, remainingWeight);
            if (carton == null) {
                carton = largest; // remainder still too big for any one carton; fill the largest.
            }
            Accumulator current = new Accumulator();
            while (!units.isEmpty()) {
                Unit unit = units.peekFirst();
                if (!current.isEmpty()
                        && (current.volume + unit.volume() > carton.usableVolume()
                        || current.weight + unit.weight() > carton.maxNet())) {
                    break;
                }
                units.pollFirst();
                current.add(unit);
                remainingVolume -= unit.volume();
                remainingWeight -= unit.weight();
            }
            assignments.add(current.close(assignments.size() + 1, carton));
        }
        return assignments;
    }

    private static Capacity smallestThatFits(List<Capacity> byCapacityDesc, double volume, double weight) {
        Capacity best = null;
        for (Capacity c : byCapacityDesc) {
            if (c.fits(volume, weight)) {
                best = c; // list is largest→smallest, so the last fitting one is the smallest
            }
        }
        return best;
    }

    private static Capacity capacity(ShipperDef s) {
        double usableVolume = innerVolume(s) * fillLevel(s);
        double tare = s.tareWeightG() == null ? 0d : s.tareWeightG().doubleValue();
        double maxNet = (s.maxWeightG() == null ? Double.MAX_VALUE : s.maxWeightG().doubleValue()) - tare;
        return new Capacity(s, usableVolume, maxNet, tare);
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
        // contents keyed by (lineNo, skuId) so a carton holding several lines stays line-traceable.
        private final Map<LineSku, Long> contents = new LinkedHashMap<>();

        private record LineSku(int lineNo, UUID skuId) {
        }

        boolean isEmpty() {
            return contents.isEmpty();
        }

        void add(Unit unit) {
            this.volume += unit.volume();
            this.weight += unit.weight();
            this.contents.merge(new LineSku(unit.lineNo(), unit.skuId()), 1L, Long::sum);
        }

        ShipperAssignment close(int seqNo, Capacity carton) {
            List<ShipperAssignment.Content> lines = new ArrayList<>();
            contents.forEach((key, qty) ->
                    lines.add(new ShipperAssignment.Content(key.lineNo(), key.skuId(), BigDecimal.valueOf(qty))));
            return new ShipperAssignment(
                    UUID.randomUUID(), seqNo, carton.shipper().id(), carton.shipper().code(), lines,
                    BigDecimal.valueOf(carton.tare() + weight), BigDecimal.valueOf(volume), null);
        }
    }

    /** Thrown when an item cannot fit any available shipper. */
    public static class CannotCubeException extends RuntimeException {
        public CannotCubeException(String message) {
            super(message);
        }
    }

    /**
     * A specific {@link CannotCubeException}: a single SKU is larger than the biggest available
     * carton, so the order cannot be cubed at all. Carries the offending line/SKU so the order
     * can be parked in CUBING_FAILED with an actionable reason.
     */
    public static class ItemDoesNotFitException extends CannotCubeException {
        private final int lineNo;
        private final UUID skuId;
        private final String largestShipperCode;

        public ItemDoesNotFitException(int lineNo, UUID skuId, String largestShipperCode, String message) {
            super(message);
            this.lineNo = lineNo;
            this.skuId = skuId;
            this.largestShipperCode = largestShipperCode;
        }

        public int lineNo() {
            return lineNo;
        }

        public UUID skuId() {
            return skuId;
        }

        public String largestShipperCode() {
            return largestShipperCode;
        }
    }
}
