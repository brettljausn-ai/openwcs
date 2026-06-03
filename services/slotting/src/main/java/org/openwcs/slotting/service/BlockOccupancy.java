package org.openwcs.slotting.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    public static List<PutawayScorer.Candidate> candidates(
            UUID skuId, List<StorageLocation> locations, List<PutawayAssignment> active) {

        Map<UUID, String> aisleByLocation = new HashMap<>();
        Map<UUID, StorageLocation> byId = new HashMap<>();
        for (StorageLocation l : locations) {
            aisleByLocation.put(l.id(), aisleOf(l));
            byId.put(l.id(), l);
        }

        Map<UUID, Integer> occupiedByLocation = new HashMap<>();
        Map<UUID, UUID> occupantByLocation = new HashMap<>();
        Map<String, Long> usedByAisle = new HashMap<>();
        Map<String, Long> skuByAisle = new HashMap<>();
        for (PutawayAssignment a : active) {
            UUID loc = a.getChosenLocationId();
            if (loc == null || !byId.containsKey(loc)) {
                continue;
            }
            occupiedByLocation.merge(loc, 1, Integer::sum);
            occupantByLocation.putIfAbsent(loc, a.getSkuId());
            String aisle = aisleByLocation.get(loc);
            usedByAisle.merge(aisle, 1L, Long::sum);
            if (skuId.equals(a.getSkuId())) {
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
                    l.distanceToExit() == null ? 0.0 : l.distanceToExit().doubleValue(),
                    occupiedByLocation.getOrDefault(l.id(), 0),
                    occupantByLocation.get(l.id()),
                    skuByAisle.getOrDefault(aisle, 0L),
                    usedByAisle.getOrDefault(aisle, 0L),
                    capacityByAisle.getOrDefault(aisle, 0L)));
        }
        return candidates;
    }

    static String aisleOf(StorageLocation l) {
        return l.aisle() == null ? "" : l.aisle();
    }

    static int laneDepth(StorageLocation l) {
        return l.laneDepth() == null || l.laneDepth() < 1 ? 1 : l.laneDepth();
    }
}
