package org.openwcs.slotting.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.openwcs.slotting.api.NotFoundException;
import org.openwcs.slotting.api.RelocationPlan;
import org.openwcs.slotting.api.RelocationPlanRequest;
import org.openwcs.slotting.api.RelocationStep;
import org.openwcs.slotting.client.InventoryClient;
import org.openwcs.slotting.client.InventoryClient.HandlingUnitView;
import org.openwcs.slotting.client.MasterDataClient;
import org.openwcs.slotting.client.MasterDataClient.CellLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Plans the dig-out for a blocked multi-deep retrieve (ADR 0009): a retrieve of an HU at cell Z N
 * is physically impossible while HUs occupy cell Z &lt; N of the same channel (same aisle + side +
 * cell X + cell Y) — each blocker must first relocate to a free location on the <em>same cell Y</em>
 * (hard constraint: one shuttle move, no lift), preferring the same aisle, then the same side, then
 * shallow (cell Z 1) targets close to the blocker; never into the channel being cleared.
 */
@Service
public class RelocationPlanService {

    private static final Logger log = LoggerFactory.getLogger(RelocationPlanService.class);

    private final MasterDataClient masterData;
    private final InventoryClient inventory;

    public RelocationPlanService(MasterDataClient masterData, InventoryClient inventory) {
        this.masterData = masterData;
        this.inventory = inventory;
    }

    public RelocationPlan plan(RelocationPlanRequest request) {
        if (request == null || request.warehouseId() == null || request.locationId() == null) {
            throw new IllegalArgumentException("warehouseId and locationId are required.");
        }
        CellLocation source = masterData.location(request.locationId());
        if (source == null) {
            throw new NotFoundException("Location", request.locationId());
        }
        if (!source.hasCell() || source.posZ() <= 1) {
            // No coordinates → no channel model; cell Z 1 → nothing can block the aisle face.
            return RelocationPlan.clear();
        }

        Map<UUID, HandlingUnitView> huByLocation = occupancy(request.warehouseId());
        if (huByLocation == null) {
            return RelocationPlan.clear();
        }

        List<CellLocation> all = masterData.locations(request.warehouseId());

        // Blockers: occupied channel siblings in front of the source slot, front-most first.
        List<CellLocation> blockers = all.stream()
                .filter(CellLocation::hasCell)
                .filter(l -> sameChannel(l, source))
                .filter(l -> l.posZ() < source.posZ())
                .filter(l -> huByLocation.containsKey(l.id()))
                .sorted(Comparator.comparing(CellLocation::posZ))
                .toList();
        if (blockers.isEmpty()) {
            return RelocationPlan.clear();
        }

        // Free storage cells outside the blocked channel — the target pool.
        List<CellLocation> pool = all.stream()
                .filter(CellLocation::hasCell)
                .filter(l -> "STORAGE".equals(l.purpose()) && (l.status() == null || "ACTIVE".equals(l.status())))
                .filter(l -> !sameChannel(l, source))
                .filter(l -> !huByLocation.containsKey(l.id()))
                .toList();

        List<RelocationStep> steps = new ArrayList<>();
        List<String> moves = new ArrayList<>();
        Set<UUID> assigned = new HashSet<>();
        for (CellLocation blocker : blockers) {
            CellLocation target = bestTarget(blocker, pool, assigned);
            if (target == null) {
                log.warn("relocation plan for {} unplannable: no free same-level (cellY {}) target for blocker"
                                + " at {}; the retrieve stays blocked until space frees up",
                        source.code(), blocker.posY(), blocker.code());
                return RelocationPlan.unplannable();
            }
            assigned.add(target.id());
            HandlingUnitView hu = huByLocation.get(blocker.id());
            steps.add(new RelocationStep(hu.huId(), hu.code(), blocker.id(), target.id()));
            moves.add("hu " + hu.code() + " " + blocker.code() + " -> " + target.code());
        }
        log.info("relocation plan for {}: {} blockers must dig out of its channel first [{}]",
                source.code(), steps.size(), String.join("; ", moves));
        return new RelocationPlan(List.copyOf(steps), false);
    }

    /**
     * The best free cell for a blocker: same cell Y (hard — the shuttle serves one level, a lift
     * move is expensive), then same aisle, then same side, then shallow (ascending cell Z), then
     * closest along the aisle (ascending |ΔcellX|); location code as the deterministic tiebreak.
     */
    private static CellLocation bestTarget(CellLocation blocker, List<CellLocation> pool, Set<UUID> assigned) {
        return pool.stream()
                .filter(l -> !assigned.contains(l.id()))
                .filter(l -> Objects.equals(l.posY(), blocker.posY()))
                .min(Comparator
                        .comparing((CellLocation l) -> Objects.equals(l.aisle(), blocker.aisle()) ? 0 : 1)
                        .thenComparing(l -> Objects.equals(l.side(), blocker.side()) ? 0 : 1)
                        .thenComparing(CellLocation::posZ)
                        .thenComparing(l -> Math.abs(l.posX() - blocker.posX()))
                        .thenComparing(l -> String.valueOf(l.code())))
                .orElse(null);
    }

    /** Same channel = same aisle + side + cell X + cell Y. */
    private static boolean sameChannel(CellLocation a, CellLocation b) {
        return Objects.equals(a.aisle(), b.aisle())
                && Objects.equals(a.side(), b.side())
                && Objects.equals(a.posX(), b.posX())
                && Objects.equals(a.posY(), b.posY());
    }

    /**
     * Live occupancy from the inventory HU registry: location → ACTIVE handling unit. Returns
     * {@code null} when occupancy is unknown (registry unreachable) — the planner then reports a
     * clear channel rather than relocating on stale guesses.
     */
    private Map<UUID, HandlingUnitView> occupancy(UUID warehouseId) {
        List<HandlingUnitView> hus;
        try {
            hus = inventory.handlingUnits(warehouseId);
        } catch (RuntimeException e) {
            log.warn("HU registry unavailable for warehouse {} — treating occupancy as unknown: {}",
                    warehouseId, e.getMessage());
            return null;
        }
        if (hus == null) {
            return null;
        }
        Map<UUID, HandlingUnitView> byLocation = new HashMap<>();
        for (HandlingUnitView hu : hus) {
            if (hu.locationId() != null && (hu.status() == null || "ACTIVE".equals(hu.status()))) {
                byLocation.putIfAbsent(hu.locationId(), hu);
            }
        }
        return byLocation;
    }
}
