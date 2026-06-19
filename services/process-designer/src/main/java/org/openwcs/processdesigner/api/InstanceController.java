package org.openwcs.processdesigner.api;

import jakarta.validation.Valid;
import java.util.List;
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
import org.springframework.web.bind.annotation.RequestParam;
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

    /** Instance monitoring list: newest-first summaries, optionally filtered, capped at limit. */
    @GetMapping
    public List<InstanceSummary> list(@RequestParam(required = false) String processKey,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) UUID warehouseId,
                                      @RequestParam(required = false) String assignedTo,
                                      @RequestParam(required = false) Integer limit) {
        return service.list(processKey, status, warehouseId, assignedTo, limit);
    }

    @PostMapping
    public ResponseEntity<InstanceView> start(@Valid @RequestBody StartInstanceRequest req,
                                              @RequestHeader(name = "X-Auth-User", required = false) String actor) {
        InstanceView view = service.start(req.processKey(), req.warehouseId(), actor == null ? "system" : actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @PostMapping("/{id}/checkpoint")
    public CheckpointResult checkpoint(@PathVariable UUID id, @Valid @RequestBody CheckpointRequest req,
                                       @RequestHeader(name = "X-Auth-User", required = false) String actor) {
        return service.checkpoint(id, req.stepId(), req.data(), req.seq(), actor);
    }

    /**
     * Record a per-screen step advance (exact resume + replay). The mobile client posts this on every
     * SCREEN advance so the server's current step + data stay exact. Idempotent on (instanceId, seq).
     */
    @PostMapping("/{id}/step")
    public InstanceView step(@PathVariable UUID id, @Valid @RequestBody StepEventRequest req,
                             @RequestHeader(name = "X-Auth-User", required = false) String actor) {
        return service.step(id, req.seq(), req.stepId(), req.stepType(), req.data(), actor);
    }

    /** Reassign a running instance to another operator (supervisor-gated by the RBAC filter). */
    @PostMapping("/{id}/reassign")
    public InstanceView reassign(@PathVariable UUID id, @Valid @RequestBody ReassignRequest req,
                                 @RequestHeader(name = "X-Auth-User", required = false) String actor) {
        return service.reassign(id, req.toUser(), actor);
    }

    /** The instance's step history in order (oldest first), for replay / resume. */
    @GetMapping("/{id}/steps")
    public List<StepEventView> steps(@PathVariable UUID id) {
        return service.steps(id);
    }

    @GetMapping("/{id}")
    public InstanceView resume(@PathVariable UUID id) {
        return service.resume(id);
    }
}
