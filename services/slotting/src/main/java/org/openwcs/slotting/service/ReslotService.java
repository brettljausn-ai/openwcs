package org.openwcs.slotting.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.openwcs.slotting.client.MasterDataClient;
import org.openwcs.slotting.client.MasterDataClient.StorageLocation;
import org.openwcs.slotting.domain.BlockPolicy;
import org.openwcs.slotting.domain.PutawayAssignment;
import org.openwcs.slotting.domain.ReslotRecommendation;
import org.openwcs.slotting.domain.StorageProfile;
import org.openwcs.slotting.repo.BlockPolicyRepository;
import org.openwcs.slotting.repo.PutawayAssignmentRepository;
import org.openwcs.slotting.repo.ReslotRecommendationRepository;
import org.openwcs.slotting.repo.StorageProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Off-peak re-slotting (ADR 0003): for each stored HU in a re-slot-enabled block, re-run the
 * put-away scorer over the current pool; if a materially better location exists (score gain beyond
 * the block's {@code reslotShiftPct} threshold) emit a {@link ReslotRecommendation} to move it.
 * This continuously corrects velocity drift, honeycombing, and aisle imbalance. Recommendations are
 * advisory; dispatching the moves is a fast-follow.
 */
@Service
public class ReslotService {

    private static final Logger log = LoggerFactory.getLogger(ReslotService.class);

    private static final List<String> ACTIVE = List.of("PLANNED", "DISPATCHED");
    private static final String RECOMMENDED = "RECOMMENDED";
    private static final int MAX_PER_RUN = 200;

    private final StorageProfileRepository profiles;
    private final BlockPolicyRepository policies;
    private final PutawayAssignmentRepository assignments;
    private final ReslotRecommendationRepository recommendations;
    private final MasterDataClient masterData;

    public ReslotService(StorageProfileRepository profiles, BlockPolicyRepository policies,
                         PutawayAssignmentRepository assignments,
                         ReslotRecommendationRepository recommendations, MasterDataClient masterData) {
        this.profiles = profiles;
        this.policies = policies;
        this.assignments = assignments;
        this.recommendations = recommendations;
        this.masterData = masterData;
    }

    /** Re-slot all enabled blocks in a warehouse. */
    @Transactional
    public List<ReslotRecommendation> recommend(UUID warehouseId) {
        List<ReslotRecommendation> all = new ArrayList<>();
        for (BlockPolicy p : policies.findByReslotEnabledTrue()) {
            if (warehouseId.equals(p.getWarehouseId())) {
                all.addAll(recommendForBlock(warehouseId, p.getBlockId()));
            }
        }
        return all;
    }

    @Transactional
    public List<ReslotRecommendation> recommendForBlock(UUID warehouseId, UUID blockId) {
        BlockPolicy policy = policies.findByBlockId(blockId).orElse(null);
        if (policy == null || !policy.isReslotEnabled()) {
            return List.of();
        }
        PutawayScorer.Policy weights = new PutawayScorer.Policy(
                policy.getWVelocity().doubleValue(), policy.getWConsolidation().doubleValue(),
                policy.getWRedundancy().doubleValue(), policy.getWBalance().doubleValue());
        double minGain = policy.getReslotShiftPct().doubleValue();

        List<StorageLocation> locations = masterData.storageLocations(warehouseId, blockId).stream()
                .filter(l -> "STORAGE".equals(l.purpose()) && (l.status() == null || "ACTIVE".equals(l.status())))
                .toList();
        if (locations.isEmpty()) {
            return List.of();
        }
        List<PutawayAssignment> active =
                assignments.findByWarehouseIdAndBlockIdAndStatusIn(warehouseId, blockId, ACTIVE);

        List<ReslotRecommendation> out = new ArrayList<>();
        for (PutawayAssignment a : active) {
            if (a.getChosenLocationId() == null) {
                continue;
            }
            UUID sku = a.getSkuId();
            UUID current = a.getChosenLocationId();

            StorageProfile profile =
                    profiles.findByWarehouseIdAndSkuIdAndBlockId(warehouseId, sku, blockId).orElse(null);
            String velocityClass = profile != null ? profile.getVelocityClass() : "B";
            boolean consolidate = profile == null || profile.isConsolidate();
            double maxAislePct = profile != null ? profile.getMaxAislePct().doubleValue()
                    : policy.getDefaultMaxAislePct().doubleValue();
            long skuTotalHu = active.stream().filter(x -> sku.equals(x.getSkuId())).count();

            List<PutawayScorer.Candidate> candidates = BlockOccupancy.candidates(sku, locations, active);
            PutawayScorer.Input input = new PutawayScorer.Input(
                    sku, BlockOccupancy.skuSet(a), velocityClass, consolidate, maxAislePct, skuTotalHu);
            List<PutawayScorer.Result> ranked = PutawayScorer.rank(input, candidates, weights);
            if (ranked.isEmpty()) {
                continue;
            }

            Optional<PutawayScorer.Result> currentResult =
                    ranked.stream().filter(r -> r.locationId().equals(current)).findFirst();
            if (currentResult.isEmpty()) {
                continue;
            }
            PutawayScorer.Result best = ranked.get(0);
            if (best.locationId().equals(current)) {
                continue;
            }
            double gain = best.score() - currentResult.get().score();
            if (gain <= minGain) {
                continue;
            }
            if (a.getHuId() != null
                    && !recommendations.findByWarehouseIdAndHuIdAndStatus(warehouseId, a.getHuId(), RECOMMENDED).isEmpty()) {
                continue; // already recommended
            }

            ReslotRecommendation rec = new ReslotRecommendation();
            rec.setWarehouseId(warehouseId);
            rec.setHuId(a.getHuId());
            rec.setSkuId(sku);
            rec.setFromLocationId(current);
            rec.setToLocationId(best.locationId());
            rec.setReason("velocity-fit gain " + round(gain));
            rec.setScoreGain(BigDecimal.valueOf(round(gain)));
            rec.setStatus(RECOMMENDED);
            out.add(recommendations.save(rec));
            log.info("reslot recommended: hu {} sku {} move from location {} to {} in block {}"
                            + " (velocity-fit score gain {} exceeds shift threshold {})",
                    a.getHuId(), sku, current, best.locationId(), blockId, round(gain), minGain);
            if (out.size() >= MAX_PER_RUN) {
                log.warn("reslot for block {} capped at {} recommendations this run;"
                        + " remaining HUs are re-evaluated on the next off-peak pass", blockId, MAX_PER_RUN);
                break;
            }
        }
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
