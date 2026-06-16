package org.openwcs.processdesigner.api;

import jakarta.validation.Valid;
import java.util.UUID;
import org.openwcs.processdesigner.service.InstanceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Instance API (spec §12). Start creates a RUNNING instance against the ACTIVE definition and
 * returns the pinned model JSON so the client can drive the screen flow offline; checkpoint runs a
 * task step on the server (idempotent on instance+step); GET resumes. All gated by
 * {@code PROCESS_DESIGN_VIEW} (operators have it); the curated task additionally enforces the
 * operator's identity at the target service.
 */
@RestController
@RequestMapping("/api/process-designer/instances")
public class InstanceController {

    private final InstanceService service;

    public InstanceController(InstanceService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<InstanceView> start(@Valid @RequestBody StartInstanceRequest req,
                                              @RequestHeader(name = "X-Auth-User", required = false) String actor) {
        InstanceView view = service.start(req.processKey(), req.warehouseId(), actor == null ? "system" : actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @PostMapping("/{id}/checkpoint")
    public CheckpointResult checkpoint(@PathVariable UUID id, @Valid @RequestBody CheckpointRequest req) {
        return service.checkpoint(id, req.stepId(), req.data());
    }

    @GetMapping("/{id}")
    public InstanceView resume(@PathVariable UUID id) {
        return service.resume(id);
    }
}
