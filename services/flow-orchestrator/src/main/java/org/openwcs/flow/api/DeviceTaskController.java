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

    @GetMapping
    public List<DeviceTaskView> byCorrelation(@RequestParam("correlationId") UUID correlationId) {
        return service.byCorrelation(correlationId);
    }
}
