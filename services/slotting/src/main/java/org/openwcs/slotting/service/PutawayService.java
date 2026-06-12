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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>Candidate locations are filtered against live inventory truth before scoring: the engine asks
 * inventory which of the block's locations physically hold stock or a handling unit
 * ({@link InventoryClient#occupiedLocations}) and drops those, so a seeded/occupied slot with no
 * slotting assignment is never chosen. That call is best-effort: if inventory is unreachable the
 * engine logs and proceeds without filtering rather than blocking put-away. Lane / aisle <i>depth</i>
 * occupancy is still derived from this service's own active assignment ledger
 * ({@code putaway_assignment}); fully reconciling that against live inventory is a fast-follow once
 * physical move execution is wired.
 */
@Service
public class PutawayService {

    private static final Logger log = LoggerFactory.getLogger(PutawayService.class);

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
        if (req.warehouseId() == null) {
            throw new IllegalArgumentException("warehouseId is required");
        }
        supersedeOpenAssignments(req);
        if (req.empty()) {
            return assignEmpty(req); // empty carrier: no SKU, stored far + moved at lower priority
        }
        boolean hasCompartments = req.compartments() != null && !req.compartments().isEmpty();
        if (req.skuId() == null && !hasCompartments) {
            throw new IllegalArgumentException("skuId or compartments are required (or set empty=true)");
        }

        // Direct-to-pick only applies to a single-SKU receipt with a forward face.
        if (req.skuId() != null) {
            PutawayDecision direct = tryDirectToPick(req);
            if (direct != null) {
                return direct;
            }
        }
        return assignToBlock(req);
    }

    /**
     * A tote can only be travelling to ONE slot: a fresh decision supersedes any earlier open
     * assignment for the same HU. Without this, every re-slot of a tote (each workplace round
     * trip asks again) left its previous PLANNED row in the active ledger forever — phantom
     * occupancy that made the aisle-share cap reject the SKU everywhere (observed live: every
     * SKU's SECOND putaway answered 409 "no feasible storage location" in the single-aisle demo
     * block, and totes circled the conveyor on the awaiting-slot sweep indefinitely).
     */
    private void supersedeOpenAssignments(PutawayRequest req) {
        if (req.huId() == null) {
            return;
        }
        for (PutawayAssignment prev : assignments.findByWarehouseIdAndHuIdAndStatusIn(
                req.warehouseId(), req.huId(), ACTIVE)) {
            prev.setStatus("SUPERSEDED");
            assignments.save(prev);
            log.info("put-away assignment {} (hu {} -> location {}) superseded by a fresh request",
                    prev.getId(), prev.getHuId(), prev.getChosenLocationId());
        }
    }

    /**
     * The handling unit physically arrived in storage: close its open assignments. Called by
     * flow-orchestrator when the ASRS STORE completes — the ledger row stops counting as
     * planned-occupancy (the cell now shows up as PHYSICALLY occupied via the inventory check).
     */
    @Transactional
    public int confirmStored(UUID warehouseId, UUID huId) {
        int closed = 0;
        for (PutawayAssignment a : assignments.findByWarehouseIdAndHuIdAndStatusIn(warehouseId, huId, ACTIVE)) {
            a.setStatus("STORED");
            assignments.save(a);
            closed++;
            log.info("put-away assignment {} confirmed STORED (hu {} at location {})",
                    a.getId(), huId, a.getChosenLocationId());
        }
        return closed;
    }

    /**
     * Place an empty handling unit: empties don't need fast access, so they go to the feasible
     * location <b>farthest from the aisle exit</b> and are flagged for <b>LOW</b> transport priority.
     * No SKU is involved (empties are a buffer for decanting). Requires an explicit block.
     */
    private PutawayDecision assignEmpty(PutawayRequest req) {
        UUID blockId = req.blockId();
        if (blockId == null) {
            throw new IllegalArgumentException("empty-HU put-away requires a blockId");
        }
        MasterDataClient.Block block = masterData.block(blockId);
        if (req.huType() != null && block != null && !huTypeAllowed(block.allowedHuTypes(), req.huType())) {
            throw new IllegalArgumentException("HU type " + req.huType() + " is not storable in block " + blockId);
        }

        List<StorageLocation> locations = masterData.storageLocations(req.warehouseId(), blockId).stream()
                .filter(l -> "STORAGE".equals(l.purpose()) && (l.status() == null || "ACTIVE".equals(l.status())))
                .filter(l -> req.huType() == null || huTypeAllowed(l.allowedHuTypes(), req.huType()))
                .toList();
        if (locations.isEmpty()) {
            throw new IllegalStateException("no storage locations in block " + blockId + " accept HU type " + req.huType());
        }
        locations = excludePhysicallyOccupied(req.warehouseId(), locations);
        if (locations.isEmpty()) {
            throw new IllegalStateException("no feasible empty-HU location in block " + blockId);
        }

        Map<UUID, Integer> occupied = new HashMap<>();
        for (PutawayAssignment a : assignments.findByWarehouseIdAndBlockIdAndStatusIn(req.warehouseId(), blockId, ACTIVE)) {
            if (a.getChosenLocationId() != null) {
                occupied.merge(a.getChosenLocationId(), 1, Integer::sum);
            }
        }
        StorageLocation chosen = locations.stream()
                .filter(l -> occupied.getOrDefault(l.id(), 0) < laneDepth(l))
                .max(java.util.Comparator.comparingDouble(PutawayService::distanceToExit))
                .orElseThrow(() -> new IllegalStateException("no feasible empty-HU location in block " + blockId));

        Map<String, Object> factors = new HashMap<>();
        factors.put("reason", "empty-far-from-exit");
        factors.put("distanceToExit", distanceToExit(chosen));
        factors.put("transportPriority", "LOW");
        PutawayAssignment saved = persist(req, null, List.of(), blockId, chosen.id(), "RESERVE", null, factors);
        log.info("put-away (empty HU): hu {} -> location {} in block {} (farthest from exit, distance {}, LOW priority)",
                req.huId(), chosen.id(), blockId, distanceToExit(chosen));
        return new PutawayDecision(saved.getId(), chosen.id(), blockId, "RESERVE", null, factors, "LOW");
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
        PutawayAssignment saved = persist(req, req.skuId(), List.of(req.skuId()), null, best.getLocationId(), "DIRECT_TO_PICK", null, factors);
        log.info("put-away (direct-to-pick): hu {} sku {} -> pick face {} (headroom {})",
                req.huId(), req.skuId(), best.getLocationId(), bestHeadroom);
        return new PutawayDecision(saved.getId(), best.getLocationId(), null, "DIRECT_TO_PICK", null, factors, "NORMAL");
    }

    private PutawayDecision assignToBlock(PutawayRequest req) {
        UUID dominantSku = dominantSku(req);
        java.util.Set<UUID> skuIds = compartmentSkuIds(req);

        StorageProfile profile = resolveProfile(req.warehouseId(), dominantSku, req.blockId());
        UUID blockId = req.blockId() != null ? req.blockId()
                : profile != null ? profile.getBlockId() : null;
        if (blockId == null) {
            // No profile and no explicit block: a warehouse with exactly ONE automated storage
            // block is still answerable — every profile-less SKU can only live there.
            blockId = onlyAutomatedBlock(req.warehouseId(), dominantSku);
        }
        if (blockId == null) {
            throw new IllegalArgumentException("no storage profile / block for sku " + dominantSku);
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
        locations = excludePhysicallyOccupied(req.warehouseId(), locations);
        if (locations.isEmpty()) {
            throw new IllegalStateException("no feasible storage location in block " + blockId);
        }

        List<PutawayAssignment> active =
                assignments.findByWarehouseIdAndBlockIdAndStatusIn(req.warehouseId(), blockId, ACTIVE);
        List<PutawayScorer.Candidate> candidates = BlockOccupancy.candidates(dominantSku, locations, active);
        long skuTotalHu = active.stream().filter(a -> BlockOccupancy.skuSet(a).contains(dominantSku)).count();

        PutawayScorer.Input input =
                new PutawayScorer.Input(dominantSku, skuIds, velocityClass, consolidate, maxAislePct, skuTotalHu);
        List<PutawayScorer.Result> ranked = PutawayScorer.rank(input, candidates, weights);
        if (ranked.isEmpty()) {
            throw new IllegalStateException("no feasible storage location in block " + blockId);
        }

        PutawayScorer.Result best = ranked.get(0);
        PutawayAssignment saved = persist(req, dominantSku, new ArrayList<>(skuIds), blockId, best.locationId(),
                "RESERVE", BigDecimal.valueOf(best.score()), best.factors());
        log.info("put-away: hu {} sku {} -> location {} in block {} (score {}, factors {})",
                req.huId(), dominantSku, best.locationId(), blockId, best.score(), best.factors());
        return new PutawayDecision(saved.getId(), best.locationId(), blockId, "RESERVE",
                BigDecimal.valueOf(best.score()), best.factors(), "NORMAL");
    }

    /** The dominant compartment SKU (most qty) for a multi-compartment HU, else the single SKU. */
    private static UUID dominantSku(PutawayRequest req) {
        if (req.compartments() == null || req.compartments().isEmpty()) {
            return req.skuId();
        }
        return req.compartments().stream()
                .filter(c -> c.skuId() != null)
                .max(java.util.Comparator.comparing(
                        c -> c.qty() == null ? BigDecimal.ZERO : c.qty()))
                .map(PutawayRequest.Compartment::skuId)
                .orElse(req.skuId());
    }

    /** The full compartment SKU set (lane-affinity key); single-SKU HUs become a one-element set. */
    private static java.util.Set<UUID> compartmentSkuIds(PutawayRequest req) {
        java.util.Set<UUID> set = new java.util.LinkedHashSet<>();
        if (req.compartments() != null) {
            for (PutawayRequest.Compartment c : req.compartments()) {
                if (c.skuId() != null) {
                    set.add(c.skuId());
                }
            }
        }
        if (set.isEmpty() && req.skuId() != null) {
            set.add(req.skuId());
        }
        return set;
    }

    /** Storage types that mean automated storage (the only-block fallback applies to these). */
    private static final java.util.Set<String> AUTOMATED_STORAGE_TYPES =
            java.util.Set.of("SHUTTLE_ASRS", "CRANE_ASRS", "AUTOSTORE", "AMR_GTP");

    /**
     * The warehouse's single automated storage block (storage type SHUTTLE_ASRS / CRANE_ASRS /
     * AUTOSTORE / AMR_GTP), or null when there are zero or several candidates — several candidates
     * still need a storage profile to choose between them (the caller keeps the existing 400).
     */
    private UUID onlyAutomatedBlock(UUID warehouseId, UUID skuId) {
        List<MasterDataClient.Block> automated;
        try {
            automated = masterData.blocks(warehouseId).stream()
                    .filter(b -> b.storageType() != null
                            && AUTOMATED_STORAGE_TYPES.contains(b.storageType()))
                    .toList();
        } catch (RuntimeException e) {
            log.warn("storage-block lookup for warehouse {} failed; cannot fall back to the only "
                    + "automated block for sku {}: {}", warehouseId, skuId, e.toString());
            return null;
        }
        if (automated.size() == 1) {
            MasterDataClient.Block only = automated.get(0);
            log.info("no storage profile for sku {}: using the only automated block {} ({})",
                    skuId, only.id(), only.storageType());
            return only.id();
        }
        if (automated.size() > 1) {
            log.warn("no storage profile for sku {} and {} automated blocks in warehouse {}: "
                    + "ambiguous, a storage profile is required", skuId, automated.size(), warehouseId);
        }
        return null;
    }

    private StorageProfile resolveProfile(UUID warehouseId, UUID skuId, UUID blockId) {
        if (blockId != null) {
            return profiles.findByWarehouseIdAndSkuIdAndBlockId(warehouseId, skuId, blockId)
                    .orElse(null);
        }
        return profiles.findByWarehouseIdAndSkuId(warehouseId, skuId).stream()
                .filter(p -> "ACTIVE".equals(p.getStatus()))
                .findFirst()
                .orElse(null);
    }

    private PutawayAssignment persist(PutawayRequest req, UUID skuId, List<UUID> skuIds, UUID blockId,
                                      UUID locationId, String mode, BigDecimal score, Map<String, Object> factors) {
        PutawayAssignment a = new PutawayAssignment();
        a.setWarehouseId(req.warehouseId());
        a.setHuId(req.huId());
        a.setSkuId(skuId);
        a.setSkuIds(skuIds);
        a.setBlockId(blockId);
        a.setChosenLocationId(locationId);
        a.setMode(mode);
        a.setScore(score);
        a.setFactors(factors);
        a.setStatus("PLANNED");
        return assignments.save(a);
    }

    /**
     * Drop any candidate that physically holds stock or a handling unit, per live inventory truth
     * (not this service's assignment ledger): only genuinely-empty locations should be scored.
     * Best-effort — if inventory is unreachable, log and keep all candidates so put-away still works.
     */
    private List<StorageLocation> excludePhysicallyOccupied(UUID warehouseId, List<StorageLocation> locations) {
        List<UUID> ids = locations.stream().map(StorageLocation::id).toList();
        try {
            java.util.Set<UUID> occupied = inventory.occupiedLocations(warehouseId, ids);
            if (occupied.isEmpty()) {
                return locations;
            }
            return locations.stream().filter(l -> !occupied.contains(l.id())).toList();
        } catch (RuntimeException e) {
            log.warn("inventory occupancy check failed; not filtering occupied locations for warehouse {}: {}",
                    warehouseId, e.toString());
            return locations;
        }
    }

    /** An empty / null allow-list accepts any HU type; otherwise the type must be listed. */
    private static boolean huTypeAllowed(List<String> allowList, String huType) {
        return allowList == null || allowList.isEmpty() || allowList.contains(huType);
    }

    private static String aisleOf(StorageLocation l) {
        return l.aisle() == null ? "" : l.aisle();
    }

    private static double distanceToExit(StorageLocation l) {
        return BlockOccupancy.effectiveDistanceToExit(l);
    }

    private static int laneDepth(StorageLocation l) {
        return l.laneDepth() == null || l.laneDepth() < 1 ? 1 : l.laneDepth();
    }
}
