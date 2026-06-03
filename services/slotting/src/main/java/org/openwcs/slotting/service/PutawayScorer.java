package org.openwcs.slotting.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pure, side-effect-free put-away scorer (no Spring / no I/O — like {@link CubingEngine}).
 *
 * <p>Given the candidate storage locations in a block (each enriched with lane + aisle occupancy)
 * it applies the hard constraints, then ranks the survivors by a single weighted score that
 * reconciles the four competing objectives the operator dials via {@link Policy}:
 *
 * <ul>
 *   <li><b>velocity-to-exit</b> — fast movers (class A) near the aisle exit, slow movers (C) deep;</li>
 *   <li><b>lane affinity (soft single-SKU-per-lane)</b> — reward filling a partial same-SKU lane,
 *       penalise mixing a different SKU into a lane (cuts honeycombing/reshuffles);</li>
 *   <li><b>redundancy</b> — spread a SKU to an aisle that doesn't hold it yet (survive an aisle outage);</li>
 *   <li><b>balance</b> — prefer emptier aisles so travel/throughput stays even.</li>
 * </ul>
 *
 * <p>The only <b>hard</b> constraints are lane capacity and the {@code maxAislePct} cap.
 * Single-SKU-per-lane is intentionally <b>soft</b> — a SKU is tried in its own lane, but a higher
 * {@code wBalance} (or other weight) can outweigh the mixing penalty when the operator wants aisle
 * balance to win. Lane affinity (same lane) and redundancy (new aisle) pull in opposite directions
 * on purpose; the weights pick the trade-off.
 */
public final class PutawayScorer {

    private PutawayScorer() {
    }

    /** A candidate storage location enriched with lane + aisle occupancy (HU counts). */
    public record Candidate(
            UUID locationId,
            String aisle,
            int laneDepth,
            double distanceToExit,
            int occupiedHu,
            Set<UUID> occupantSkuIds, // union of the SKU sets of HUs in this lane; empty if the lane is empty
            long aisleSkuHu,          // HUs containing the incoming dominant SKU already in this aisle
            long aisleUsedHu,         // HUs of all SKUs in this aisle
            long aisleCapacityHu      // total HU capacity of this aisle
    ) {
    }

    /**
     * The incoming put-away + the SKU's current spread, plus the resolved policy.
     * {@code skuId} is the dominant compartment SKU (drives velocity, redundancy and the aisle cap);
     * {@code skuIds} is the full compartment SKU set (drives lane affinity — single-SKU HUs have a
     * one-element set).
     */
    public record Input(
            UUID skuId,
            Set<UUID> skuIds,
            String velocityClass, // A | B | C
            boolean consolidate,
            double maxAislePct,   // hard cap: share of the dominant SKU's stock allowed in one aisle
            long skuTotalHu       // HUs of the dominant SKU across the whole block (for the cap)
    ) {
    }

    public record Policy(double wVelocity, double wConsolidation, double wRedundancy, double wBalance) {
    }

    public record Result(UUID locationId, double score, Map<String, Object> factors) {
    }

    /**
     * Returns the feasible candidates ranked best-first. Empty if none are feasible.
     */
    public static List<Result> rank(Input in, List<Candidate> candidates, Policy policy) {
        // Velocity needs the distance range across the feasible set to normalise.
        List<Candidate> feasible = new ArrayList<>();
        for (Candidate c : candidates) {
            if (isFeasible(in, c)) {
                feasible.add(c);
            }
        }
        if (feasible.isEmpty()) {
            return List.of();
        }

        double minDist = feasible.stream().mapToDouble(Candidate::distanceToExit).min().orElse(0);
        double maxDist = feasible.stream().mapToDouble(Candidate::distanceToExit).max().orElse(0);
        double span = maxDist - minDist;

        List<Result> results = new ArrayList<>(feasible.size());
        for (Candidate c : feasible) {
            double norm = span <= 0 ? 0.0 : (c.distanceToExit() - minDist) / span; // 0 = nearest exit
            double velocityFit = velocityFit(in.velocityClass(), norm);
            double consolidation = laneAffinity(in, c);
            double redundancy = c.aisleSkuHu() == 0 ? 1.0 : 0.0; // reward an aisle without this SKU yet
            double balance = c.aisleCapacityHu() <= 0 ? 0.0 : 1.0 - ((double) c.aisleUsedHu() / c.aisleCapacityHu());

            double score = policy.wVelocity() * velocityFit
                    + policy.wConsolidation() * consolidation
                    + policy.wRedundancy() * redundancy
                    + policy.wBalance() * balance;

            Map<String, Object> factors = new LinkedHashMap<>();
            factors.put("aisle", c.aisle());
            factors.put("velocityFit", round(velocityFit));
            factors.put("consolidation", consolidation);
            factors.put("redundancy", redundancy);
            factors.put("balance", round(balance));
            factors.put("occupiedHu", c.occupiedHu());
            factors.put("laneDepth", c.laneDepth());
            results.add(new Result(c.locationId(), round(score), factors));
        }

        // Best score first; tie-break prefers filling an already-started same-SKU lane (less
        // honeycombing), then the shallower lane.
        results.sort((a, b) -> {
            int byScore = Double.compare(b.score(), a.score());
            if (byScore != 0) {
                return byScore;
            }
            Candidate ca = byId(feasible, a.locationId());
            Candidate cb = byId(feasible, b.locationId());
            int byFill = Integer.compare(cb.occupiedHu(), ca.occupiedHu());
            if (byFill != 0) {
                return byFill;
            }
            return Integer.compare(ca.laneDepth(), cb.laneDepth());
        });
        return results;
    }

    /**
     * Hard constraints: lane capacity and the max-%-per-aisle cap. Single-SKU-per-lane is NOT
     * hard — it is a soft preference (see {@link #laneAffinity}) so aisle balance (or another
     * weighted objective) can outweigh it when the operator configures it that way.
     */
    private static boolean isFeasible(Input in, Candidate c) {
        if (c.occupiedHu() >= c.laneDepth()) {
            return false; // lane full
        }
        // Prospective share of the SKU's stock that would sit in this aisle after placement.
        double prospective = (c.aisleSkuHu() + 1.0) / (in.skuTotalHu() + 1.0);
        return !(prospective > in.maxAislePct() && c.aisleSkuHu() > 0);
    }

    /**
     * Lane affinity (soft single-SKU-per-lane, generalised to the compartment SKU set): rewards a
     * lane whose existing SKU set exactly matches the incoming HU's set and penalises mixing a
     * different set. Returns +1 same-set, 0 empty, −1 different-set. A single-SKU HU is just a
     * one-element set, so this reduces to the original single-SKU-per-lane behaviour. Weighted by
     * {@code wConsolidation}; gated by {@code consolidate}.
     */
    private static double laneAffinity(Input in, Candidate c) {
        if (!in.consolidate() || c.occupantSkuIds() == null || c.occupantSkuIds().isEmpty()) {
            return 0.0; // empty lane, or affinity disabled
        }
        return c.occupantSkuIds().equals(in.skuIds()) ? 1.0 : -1.0;
    }

    /** A movers want norm≈0 (near exit), C movers want norm≈1 (deep), B is indifferent. */
    private static double velocityFit(String velocityClass, double normDistance) {
        return switch (velocityClass == null ? "B" : velocityClass) {
            case "A" -> 1.0 - normDistance;
            case "C" -> normDistance;
            default -> 0.5;
        };
    }

    private static Candidate byId(List<Candidate> candidates, UUID id) {
        for (Candidate c : candidates) {
            if (c.locationId().equals(id)) {
                return c;
            }
        }
        throw new IllegalStateException("candidate vanished: " + id);
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
