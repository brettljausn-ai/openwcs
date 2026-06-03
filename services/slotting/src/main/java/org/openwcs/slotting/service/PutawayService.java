package org.openwcs.slotting.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.openwcs.slotting.api.PutawayDecision;
import org.openwcs.slotting.api.PutawayRequest;
import org.openwcs.slotting.client.InventoryClient;
import org.openwcs.slotting.client.MasterDataClient;
import org.openwcs.slotting.client.MasterDataClient.StorageLocation;
import org.openwcs.slotting.domain.BlockPolicy;
import org.openwcs.slotting.domain.PickSlot;
import org.openwcs.slotting.domain.PutawayAssignment;
import org.openwcs.slotting.domain.StorageProfile;
import org.openwcs.slotting.repo.BlockPolicyRepository;
import org.openwcs.slotting.repo.PickSlotRepository;
import org.openwcs.slotting.repo.PutawayAssignmentRepository;
import org.openwcs.slotting.repo.StorageProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides where an inbound handling unit goes (ADR 0003):
 * <ol>
 *   <li><b>direct-to-pick</b> — if the SKU has a pick face flagged {@code directToPick} with
 *       headroom below max, route straight there (cross-dock to the forward face);</li>
 *   <li>otherwise resolve the SKU's storage block and let {@link PutawayScorer} pick the best
 *       storage location from the block pool.</li>
 * </ol>
 *
 * <p>Lane / aisle occupancy is derived from this service's own active assignment ledger
 * ({@code putaway_assignment}); reconciling that against live inventory truth is a fast-follow
 * once physical move execution is wired.
 */
@Service
public class PutawayService {

    private static final List<String> ACTIVE = List.of("PLANNED", "DISPATCHED");

    private final StorageProfileRepository profiles;
    private final BlockPolicyRepository policies;
    private final PickSlotRepository pickSlots;
    private final PutawayAssignmentRepository assignments;
    private final MasterDataClient masterData;
    private final InventoryClient inventory;

    public PutawayService(StorageProfileRepository profiles, BlockPolicyRepository policies,
                          PickSlotRepository pickSlots, PutawayAssignmentRepository assignments,
                          MasterDataClient masterData, InventoryClient inventory) {
        this.profiles = profiles;
        this.policies = policies;
        this.pickSlots = pickSlots;
        this.assignments = assignments;
        this.masterData = masterData;
        this.inventory = inventory;
    }

    @Transactional
    public PutawayDecision assign(PutawayRequest req) {
        if (req.warehouseId() == null || req.skuId() == null) {
            throw new IllegalArgumentException("warehouseId and skuId are required");
        }

        PutawayDecision direct = tryDirectToPick(req);
        if (direct != null) {
            return direct;
        }
        return assignToBlock(req);
    }

    /** Cross-dock the receipt straight to a forward pick face when one has room below max. */
    private PutawayDecision tryDirectToPick(PutawayRequest req) {
        PickSlot best = null;
        BigDecimal bestHeadroom = BigDecimal.ZERO;
        for (PickSlot slot : pickSlots.findByWarehouseIdAndSkuId(req.warehouseId(), req.skuId())) {
            if (!slot.isDirectToPick() || !"ACTIVE".equals(slot.getStatus())) {
                continue;
            }
            if (req.uomId() != null && !req.uomId().equals(slot.getUomId())) {
                continue;
            }
            BigDecimal onHand = inventory.onHandAtLocation(req.warehouseId(), req.skuId(), slot.getLocationId());
            BigDecimal headroom = slot.getMaxQty().subtract(onHand == null ? BigDecimal.ZERO : onHand);
            if (headroom.signum() > 0 && headroom.compareTo(bestHeadroom) > 0) {
                best = slot;
                bestHeadroom = headroom;
            }
        }
        if (best == null) {
            return null;
        }
        Map<String, Object> factors = Map.of("reason", "direct-to-pick", "headroom", bestHeadroom);
        PutawayAssignment saved = persist(req, null, best.getLocationId(), "DIRECT_TO_PICK", null, factors);
        return new PutawayDecision(saved.getId(), best.getLocationId(), null, "DIRECT_TO_PICK", null, factors);
    }

    private PutawayDecision assignToBlock(PutawayRequest req) {
        StorageProfile profile = resolveProfile(req);
        UUID blockId = req.blockId() != null ? req.blockId()
                : profile != null ? profile.getBlockId() : null;
        if (blockId == null) {
            throw new IllegalArgumentException("no storage profile / block for sku " + req.skuId());
        }

        BlockPolicy policy = policies.findByBlockId(blockId).orElse(null);
        PutawayScorer.Policy weights = policy == null
                ? new PutawayScorer.Policy(1, 1, 1, 1)
                : new PutawayScorer.Policy(
                        policy.getWVelocity().doubleValue(), policy.getWConsolidation().doubleValue(),
                        policy.getWRedundancy().doubleValue(), policy.getWBalance().doubleValue());

        double maxAislePct = profile != null ? profile.getMaxAislePct().doubleValue()
                : policy != null ? policy.getDefaultMaxAislePct().doubleValue() : 0.5;
        String velocityClass = profile != null ? profile.getVelocityClass() : "B";
        boolean consolidate = profile == null || profile.isConsolidate();

        // The automated area only stores HU types it permits (different per area).
        MasterDataClient.Block block = masterData.block(blockId);
        if (req.huType() != null && block != null && !huTypeAllowed(block.allowedHuTypes(), req.huType())) {
            throw new IllegalArgumentException(
                    "HU type " + req.huType() + " is not storable in block " + blockId);
        }

        List<StorageLocation> locations = masterData.storageLocations(req.warehouseId(), blockId).stream()
                .filter(l -> "STORAGE".equals(l.purpose()) && (l.status() == null || "ACTIVE".equals(l.status())))
                .filter(l -> req.huType() == null || huTypeAllowed(l.allowedHuTypes(), req.huType()))
                .toList();
        if (locations.isEmpty()) {
            throw new IllegalStateException("no storage locations in block " + blockId + " accept HU type " + req.huType());
        }

        List<PutawayScorer.Candidate> candidates = buildCandidates(req, blockId, locations);
        long skuTotalHu = candidates.isEmpty() ? 0
                : assignments.findByWarehouseIdAndBlockIdAndStatusIn(req.warehouseId(), blockId, ACTIVE).stream()
                        .filter(a -> req.skuId().equals(a.getSkuId())).count();

        PutawayScorer.Input input =
                new PutawayScorer.Input(req.skuId(), velocityClass, consolidate, maxAislePct, skuTotalHu);
        List<PutawayScorer.Result> ranked = PutawayScorer.rank(input, candidates, weights);
        if (ranked.isEmpty()) {
            throw new IllegalStateException("no feasible storage location in block " + blockId);
        }

        PutawayScorer.Result best = ranked.get(0);
        PutawayAssignment saved = persist(req, blockId, best.locationId(), "RESERVE",
                BigDecimal.valueOf(best.score()), best.factors());
        return new PutawayDecision(saved.getId(), best.locationId(), blockId, "RESERVE",
                BigDecimal.valueOf(best.score()), best.factors());
    }

    private List<PutawayScorer.Candidate> buildCandidates(
            PutawayRequest req, UUID blockId, List<StorageLocation> locations) {
        Map<UUID, String> aisleByLocation = new HashMap<>();
        Map<UUID, StorageLocation> byId = new HashMap<>();
        for (StorageLocation l : locations) {
            aisleByLocation.put(l.id(), aisleOf(l));
            byId.put(l.id(), l);
        }

        // Occupancy from the active assignment ledger.
        Map<UUID, Integer> occupiedByLocation = new HashMap<>();
        Map<UUID, UUID> occupantByLocation = new HashMap<>();
        Map<String, Long> usedByAisle = new HashMap<>();
        Map<String, Long> skuByAisle = new HashMap<>();
        for (PutawayAssignment a : assignments.findByWarehouseIdAndBlockIdAndStatusIn(req.warehouseId(), blockId, ACTIVE)) {
            UUID loc = a.getChosenLocationId();
            if (loc == null || !byId.containsKey(loc)) {
                continue;
            }
            occupiedByLocation.merge(loc, 1, Integer::sum);
            occupantByLocation.putIfAbsent(loc, a.getSkuId());
            String aisle = aisleByLocation.get(loc);
            usedByAisle.merge(aisle, 1L, Long::sum);
            if (req.skuId().equals(a.getSkuId())) {
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

    private StorageProfile resolveProfile(PutawayRequest req) {
        if (req.blockId() != null) {
            return profiles.findByWarehouseIdAndSkuIdAndBlockId(req.warehouseId(), req.skuId(), req.blockId())
                    .orElse(null);
        }
        return profiles.findByWarehouseIdAndSkuId(req.warehouseId(), req.skuId()).stream()
                .filter(p -> "ACTIVE".equals(p.getStatus()))
                .findFirst()
                .orElse(null);
    }

    private PutawayAssignment persist(PutawayRequest req, UUID blockId, UUID locationId, String mode,
                                      BigDecimal score, Map<String, Object> factors) {
        PutawayAssignment a = new PutawayAssignment();
        a.setWarehouseId(req.warehouseId());
        a.setHuId(req.huId());
        a.setSkuId(req.skuId());
        a.setBlockId(blockId);
        a.setChosenLocationId(locationId);
        a.setMode(mode);
        a.setScore(score);
        a.setFactors(factors);
        a.setStatus("PLANNED");
        return assignments.save(a);
    }

    /** An empty / null allow-list accepts any HU type; otherwise the type must be listed. */
    private static boolean huTypeAllowed(List<String> allowList, String huType) {
        return allowList == null || allowList.isEmpty() || allowList.contains(huType);
    }

    private static String aisleOf(StorageLocation l) {
        return l.aisle() == null ? "" : l.aisle();
    }

    private static int laneDepth(StorageLocation l) {
        return l.laneDepth() == null || l.laneDepth() < 1 ? 1 : l.laneDepth();
    }
}
