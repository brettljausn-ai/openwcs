package org.openwcs.slotting.api;

import java.util.List;
import java.util.UUID;
import org.openwcs.common.security.AccessControl;
import org.openwcs.slotting.repo.SkuVelocityRepository;
import org.openwcs.slotting.service.VelocityDashboardService;
import org.openwcs.slotting.velocity.VelocityClassifier;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Self-taught ABC velocity API: read a SKU's learned score/class and trigger an on-demand
 * recompute. The off-peak {@code VelocityScheduler} runs the same recompute on the slotting cron.
 */
@RestController
@RequestMapping("/api/slotting/velocity")
public class VelocityController {

    private final SkuVelocityRepository velocity;
    private final VelocityClassifier classifier;
    private final VelocityDashboardService dashboard;

    public VelocityController(SkuVelocityRepository velocity, VelocityClassifier classifier,
                             VelocityDashboardService dashboard) {
        this.velocity = velocity;
        this.classifier = classifier;
        this.dashboard = dashboard;
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

    /** Read-only ABC / movers aggregation for a warehouse (Dashboards &amp; alerting). */
    @GetMapping("/abc")
    public AbcMoversView abc(@RequestHeader(name = "X-Auth-Warehouses", required = false) String warehouses,
                             @RequestParam UUID warehouseId) {
        if (!AccessControl.warehouseAllowed(warehouses, warehouseId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not permitted for this warehouse.");
        }
        return dashboard.build(warehouseId);
    }
}
