package org.openwcs.slotting.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 *   <li><b>consolidation</b> — fill a partial same-SKU lane (cuts honeycombing/reshuffles);</li>
 *   <li><b>redundancy</b> — spread a SKU to an aisle that doesn't hold it yet (survive an aisle outage);</li>
 *   <li><b>balance</b> — prefer emptier aisles so travel/throughput stays even.</li>
 * </ul>
 *
 * Consolidation (same lane) and redundancy (new aisle) pull in opposite directions on purpose; the
 * weights pick the trade-off and the {@code maxAislePct} cap is the hard guard against over-
 * concentration.
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
            UUID occupantSkuId,   // SKU currently in this lane, or null if empty
            long aisleSkuHu,      // HUs of the incoming SKU already in this aisle
            long aisleUsedHu,     // HUs of all SKUs in this aisle
            long aisleCapacityHu  // total HU capacity of this aisle
    ) {
    }

    /** The incoming put-away + the SKU's current spread, plus the resolved policy. */
    public record Input(
            UUID skuId,
            String velocityClass, // A | B | C
            boolean consolidate,
            double maxAislePct,   // hard cap: share of the SKU's stock allowed in one aisle
            long skuTotalHu       // HUs of this SKU across the whole block (for the cap)
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
            double consolidation = (in.consolidate() && in.skuId().equals(c.occupantSkuId())) ? 1.0 : 0.0;
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

    /** Hard constraints: lane capacity, single-SKU-per-lane, and the max-%-per-aisle cap. */
    private static boolean isFeasible(Input in, Candidate c) {
        if (c.occupiedHu() >= c.laneDepth()) {
            return false; // lane full
        }
        if (c.occupantSkuId() != null && !c.occupantSkuId().equals(in.skuId())) {
            return false; // single-SKU-per-lane
        }
        // Prospective share of the SKU's stock that would sit in this aisle after placement.
        double prospective = (c.aisleSkuHu() + 1.0) / (in.skuTotalHu() + 1.0);
        return !(prospective > in.maxAislePct() && c.aisleSkuHu() > 0);
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
