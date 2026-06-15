package org.openwcs.slotting.api;

import java.util.UUID;
import org.openwcs.common.security.AccessControl;
import org.openwcs.slotting.service.LaneReconciliationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Lane-depth ledger reconciliation (ADR 0003 fast-follow): compares slotting's assumed occupied
 * depth of each multi-deep lane against inventory's actual occupancy, corrects ghost assignments,
 * and returns the drift report. Off-peak the same sweep runs on a ShedLock-guarded schedule
 * ({@link org.openwcs.slotting.service.LaneReconciliationScheduler}).
 */
@RestController
@RequestMapping("/api/slotting/lanes")
public class LaneReconciliationController {

    private final LaneReconciliationService reconciliation;

    public LaneReconciliationController(LaneReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    /**
     * Reconcile a warehouse now — a single block if {@code blockId} is given, else all blocks.
     * A write (it corrects ghost assignments), so it requires OPERATOR/ADMIN and warehouse scope.
     */
    @PostMapping("/reconcile")
    public LaneReconciliationReport reconcile(
            @RequestParam UUID warehouseId,
            @RequestParam(required = false) UUID blockId,
            @RequestHeader(name = "X-Auth-Roles", required = false) String roles,
            @RequestHeader(name = "X-Auth-Warehouses", required = false) String warehouses) {
        DispatchAccess.requireOperator(roles);
        if (!AccessControl.warehouseAllowed(warehouses, warehouseId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not permitted for this warehouse.");
        }
        return blockId != null
                ? reconciliation.reconcileBlock(warehouseId, blockId)
                : reconciliation.reconcile(warehouseId);
    }
}
