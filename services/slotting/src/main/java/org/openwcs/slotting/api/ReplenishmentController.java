package org.openwcs.slotting.api;

import java.util.List;
import java.util.UUID;
import org.openwcs.slotting.domain.ReplenishmentTask;
import org.openwcs.slotting.repo.ReplenishmentTaskRepository;
import org.openwcs.slotting.service.ReplenishmentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Replenishment planning + task list (ADR 0003). */
@RestController
@RequestMapping("/api/slotting/replenishment")
public class ReplenishmentController {

    private final ReplenishmentService replenishment;
    private final ReplenishmentTaskRepository tasks;

    public ReplenishmentController(ReplenishmentService replenishment, ReplenishmentTaskRepository tasks) {
        this.replenishment = replenishment;
        this.tasks = tasks;
    }

    /** Reactive below-min pass (raises EMERGENCY/SCHEDULED tasks). */
    @PostMapping("/plan")
    public List<ReplenishmentTask> plan(@RequestParam UUID warehouseId) {
        return replenishment.planBelowMin(warehouseId);
    }

    /** Opportunistic top-off-to-max pass. */
    @PostMapping("/top-off")
    public List<ReplenishmentTask> topOff(@RequestParam UUID warehouseId) {
        return replenishment.topOff(warehouseId);
    }

    @GetMapping("/tasks")
    public List<ReplenishmentTask> list(@RequestParam UUID warehouseId,
                                        @RequestParam(defaultValue = "PLANNED") String status) {
        return tasks.findByWarehouseIdAndStatus(warehouseId, status);
    }
}
