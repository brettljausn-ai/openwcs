package org.openwcs.slotting.api;

import java.util.UUID;
import org.openwcs.common.security.AccessControl;
import org.openwcs.slotting.domain.BlockPolicy;
import org.openwcs.slotting.repo.BlockPolicyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Per-block put-away scoring policy. GET returns the policy (or 404); PUT upserts by block. */
@RestController
@RequestMapping("/api/slotting/block-policies")
public class BlockPolicyController {

    private static final Logger log = LoggerFactory.getLogger(BlockPolicyController.class);

    private final BlockPolicyRepository policies;

    public BlockPolicyController(BlockPolicyRepository policies) {
        this.policies = policies;
    }

    @GetMapping("/{blockId}")
    public BlockPolicy get(@PathVariable UUID blockId) {
        return policies.findByBlockId(blockId).orElseThrow(() -> new NotFoundException("BlockPolicy", blockId));
    }

    @PutMapping("/{blockId}")
    public BlockPolicy upsert(@PathVariable UUID blockId,
                              @RequestHeader(name = "X-Auth-Warehouses", required = false) String warehouses,
                              @RequestBody BlockPolicy body) {
        requireWarehouse(warehouses, body.getWarehouseId());
        BlockPolicy policy = policies.findByBlockId(blockId).orElse(null);
        boolean created = policy == null;
        if (policy == null) {
            policy = new BlockPolicy();
        }
        policy.setBlockId(blockId);
        policy.setWarehouseId(body.getWarehouseId());
        policy.setWVelocity(body.getWVelocity());
        policy.setWConsolidation(body.getWConsolidation());
        policy.setWRedundancy(body.getWRedundancy());
        policy.setWBalance(body.getWBalance());
        policy.setDefaultMaxAislePct(body.getDefaultMaxAislePct());
        policy.setMinAislesA(body.getMinAislesA());
        policy.setMinAislesB(body.getMinAislesB());
        policy.setMinAislesC(body.getMinAislesC());
        policy.setReslotEnabled(body.isReslotEnabled());
        policy.setReslotShiftPct(body.getReslotShiftPct());
        policy.setOffpeakCron(body.getOffpeakCron());
        BlockPolicy saved = policies.save(policy);
        log.info("block policy {} for block {} (warehouse {}): weights velocity {}, consolidation {},"
                        + " redundancy {}, balance {}; reslot {} (shift threshold {}), off-peak cron {}",
                created ? "created" : "updated", blockId, saved.getWarehouseId(),
                saved.getWVelocity(), saved.getWConsolidation(), saved.getWRedundancy(), saved.getWBalance(),
                saved.isReslotEnabled() ? "enabled" : "disabled", saved.getReslotShiftPct(), saved.getOffpeakCron());
        return saved;
    }

    /** 403 if the caller is warehouse-scoped and the body targets a warehouse outside their set. */
    private static void requireWarehouse(String warehouses, UUID warehouseId) {
        if (!AccessControl.warehouseAllowed(warehouses, warehouseId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not permitted for this warehouse.");
        }
    }
}
