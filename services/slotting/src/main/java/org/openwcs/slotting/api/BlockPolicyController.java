package org.openwcs.slotting.api;

import java.util.UUID;
import org.openwcs.slotting.domain.BlockPolicy;
import org.openwcs.slotting.repo.BlockPolicyRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Per-block put-away scoring policy. GET returns the policy (or 404); PUT upserts by block. */
@RestController
@RequestMapping("/api/slotting/block-policies")
public class BlockPolicyController {

    private final BlockPolicyRepository policies;

    public BlockPolicyController(BlockPolicyRepository policies) {
        this.policies = policies;
    }

    @GetMapping("/{blockId}")
    public BlockPolicy get(@PathVariable UUID blockId) {
        return policies.findByBlockId(blockId).orElseThrow(() -> new NotFoundException("BlockPolicy", blockId));
    }

    @PutMapping("/{blockId}")
    public BlockPolicy upsert(@PathVariable UUID blockId, @RequestBody BlockPolicy body) {
        BlockPolicy policy = policies.findByBlockId(blockId).orElseGet(BlockPolicy::new);
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
        return policies.save(policy);
    }
}
