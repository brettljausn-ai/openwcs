package org.openwcs.slotting.api;

import java.util.List;
import java.util.UUID;
import org.openwcs.slotting.repo.SkuVelocityRepository;
import org.openwcs.slotting.velocity.VelocityClassifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-taught ABC velocity API: read a SKU's learned score/class and trigger an on-demand
 * recompute. The off-peak {@code VelocityScheduler} runs the same recompute on the slotting cron.
 */
@RestController
@RequestMapping("/api/slotting/velocity")
public class VelocityController {

    private final SkuVelocityRepository velocity;
    private final VelocityClassifier classifier;

    public VelocityController(SkuVelocityRepository velocity, VelocityClassifier classifier) {
        this.velocity = velocity;
        this.classifier = classifier;
    }

    /** Current learned velocity for one SKU. */
    @GetMapping
    public VelocityView get(@RequestParam UUID warehouseId, @RequestParam UUID skuId) {
        return velocity.findByWarehouseIdAndSkuId(warehouseId, skuId)
                .map(VelocityView::of)
                .orElseThrow(() -> new NotFoundException("SkuVelocity", warehouseId + "/" + skuId));
    }

    /** All learned velocities in a warehouse, highest decayed score first. */
    @GetMapping("/scores")
    public List<VelocityView> list(@RequestParam UUID warehouseId) {
        return velocity.findByWarehouseIdOrderByScoreDesc(warehouseId).stream()
                .map(VelocityView::of)
                .toList();
    }

    /** Recompute the recency-weighted scores + A/B/C classes for a warehouse now. */
    @PostMapping("/recompute")
    public List<VelocityView> recompute(@RequestParam UUID warehouseId) {
        return classifier.recompute(warehouseId).stream()
                .map(VelocityView::of)
                .toList();
    }
}
