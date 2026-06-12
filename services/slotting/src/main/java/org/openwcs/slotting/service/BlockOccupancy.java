package org.openwcs.slotting.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.openwcs.slotting.client.MasterDataClient.StorageLocation;
import org.openwcs.slotting.domain.PutawayAssignment;

/**
 * Builds {@link PutawayScorer.Candidate}s for a block from the storage locations and the active
 * assignment ledger, computing per-lane and per-aisle occupancy in HU counts. Shared by the
 * put-away and re-slot engines.
 */
public final class BlockOccupancy {

    private BlockOccupancy() {
    }

    /**
     * @param dominantSku the incoming HU's dominant compartment SKU — drives the per-aisle count
     *                    used for redundancy + the max-%-per-aisle cap.
     */
    public static List<PutawayScorer.Candidate> candidates(
            UUID dominantSku, List<StorageLocation> locations, List<PutawayAssignment> active) {

        Map<UUID, String> aisleByLocation = new HashMap<>();
        Map<UUID, StorageLocation> byId = new HashMap<>();
        for (StorageLocation l : locations) {
            aisleByLocation.put(l.id(), aisleOf(l));
            byId.put(l.id(), l);
        }

        Map<UUID, Integer> occupiedByLocation = new HashMap<>();
        Map<UUID, Set<UUID>> occupantSkusByLocation = new HashMap<>();
        Map<String, Long> usedByAisle = new HashMap<>();
        Map<String, Long> skuByAisle = new HashMap<>();
        for (PutawayAssignment a : active) {
            UUID loc = a.getChosenLocationId();
            if (loc == null || !byId.containsKey(loc)) {
                continue;
            }
            Set<UUID> skus = skuSet(a);
            occupiedByLocation.merge(loc, 1, Integer::sum);
            occupantSkusByLocation.computeIfAbsent(loc, k -> new HashSet<>()).addAll(skus);
            String aisle = aisleByLocation.get(loc);
            usedByAisle.merge(aisle, 1L, Long::sum);
            if (dominantSku != null && skus.contains(dominantSku)) {
                skuByAisle.merge(aisle, 1L, Long::sum);
            }
        }

        Map<String, Long> capacityByAisle = new HashMap<>();
        for (StorageLocation l : locations) {
            capacityByAisle.merge(aisleOf(l), (long) laneDepth(l), Long::sum);
        }

        List<PutawayScorer.Candidate> candidates = new ArrayList<>(locations.size());
        for (StorageLocation l : locations) {
            String aisle = aisleOf(l);
            candidates.add(new PutawayScorer.Candidate(
                    l.id(),
                    aisle,
                    laneDepth(l),
                    effectiveDistanceToExit(l),
                    occupiedByLocation.getOrDefault(l.id(), 0),
                    occupantSkusByLocation.getOrDefault(l.id(), Set.of()),
                    skuByAisle.getOrDefault(aisle, 0L),
                    usedByAisle.getOrDefault(aisle, 0L),
                    capacityByAisle.getOrDefault(aisle, 0L)));
        }
        return candidates;
    }

    /** The SKU set an assignment occupies: its compartment set, falling back to its single SKU. */
    static Set<UUID> skuSet(PutawayAssignment a) {
        if (a.getSkuIds() != null && !a.getSkuIds().isEmpty()) {
            return new HashSet<>(a.getSkuIds());
        }
        return a.getSkuId() == null ? Set.of() : Set.of(a.getSkuId());
    }

    static String aisleOf(StorageLocation l) {
        return l.aisle() == null ? "" : l.aisle();
    }

    static int laneDepth(StorageLocation l) {
        return l.laneDepth() == null || l.laneDepth() < 1 ? 1 : l.laneDepth();
    }

    /**
     * The distance the velocity-to-exit objective ranks by. An explicit {@code distance_to_exit}
     * always wins; when it is not maintained (the common case for generated racks) a
     * rank-equivalent distance is derived from the cell coordinate, assuming the aisle's
     * in/outfeed sits at position 1, ground level, lane face: (posX-1) + (posY-1) + (posZ-1).
     * Unitless — only the ordering matters to the scorer. Without this fallback every candidate
     * collapses to distance 0 and placement degrades to list order instead of "fast movers near
     * the port".
     */
    static double effectiveDistanceToExit(StorageLocation l) {
        if (l.distanceToExit() != null) {
            return l.distanceToExit().doubleValue();
        }
        double d = 0.0;
        if (l.posX() != null) {
            d += l.posX() - 1;
        }
        if (l.posY() != null) {
            d += l.posY() - 1;
        }
        if (l.posZ() != null) {
            d += l.posZ() - 1;
        }
        return d;
    }
}
