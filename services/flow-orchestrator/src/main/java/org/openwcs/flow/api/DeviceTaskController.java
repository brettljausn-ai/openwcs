package org.openwcs.flow.api;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.openwcs.flow.service.DeviceTaskService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Device-task API (build.md §8). Coarse RBAC is enforced by {@code RbacFilter}; the actor is
 * taken from the gateway-provided {@code X-Auth-User} header for auditing.
 */
@RestController
@RequestMapping("/api/flow/device-tasks")
public class DeviceTaskController {

    private final DeviceTaskService service;

    public DeviceTaskController(DeviceTaskService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<DeviceTaskView> request(
            @Valid @RequestBody RequestDeviceTask request,
            @RequestHeader(value = "X-Auth-User", required = false) String actor) {
        DeviceTaskView view = service.request(request, actor == null ? "system" : actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @GetMapping("/{id}")
    public DeviceTaskView get(@PathVariable UUID id) {
        return service.get(id);
    }

    /**
     * Async result callback: an asynchronous adapter/emulator POSTs the terminal outcome of a
     * DISPATCHED task here. Idempotent (an already-terminal task is left unchanged).
     */
    @PostMapping("/{id}/result")
    public DeviceTaskView result(@PathVariable UUID id, @RequestBody DeviceTaskResultCallback body) {
        return service.completeFromCallback(id, body.status(), body.detail(), body.resultPayload());
    }

    /**
     * Lists device tasks for the transport overview. With {@code correlationId} it returns that
     * group oldest-first (the original behaviour); otherwise it returns recent tasks newest-first,
     * with optional {@code warehouseId}/{@code status}/{@code family}/{@code equipmentId} filters
     * and a {@code limit} (default 100, capped at 500).
     */
    @GetMapping
    public List<DeviceTaskView> list(
            @RequestParam(value = "correlationId", required = false) UUID correlationId,
            @RequestParam(value = "warehouseId", required = false) UUID warehouseId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "family", required = false) String family,
            @RequestParam(value = "equipmentId", required = false) UUID equipmentId,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        if (correlationId != null) {
            return service.byCorrelation(correlationId);
        }
        return service.search(warehouseId, status, family, equipmentId, limit);
    }
}
