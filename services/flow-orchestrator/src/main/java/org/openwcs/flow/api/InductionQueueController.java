package org.openwcs.flow.api;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.openwcs.flow.service.InductionQueueService;
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
 * The flow-owned inbound induction / presentation queue API (ADR-0007 §3). Coarse RBAC is enforced
 * by {@code RbacFilter}; the actor is the gateway-provided {@code X-Auth-User} header (as
 * {@code DeviceTaskController}).
 */
@RestController
@RequestMapping("/api/flow/induction")
public class InductionQueueController {

    private final InductionQueueService service;

    public InductionQueueController(InductionQueueService service) {
        this.service = service;
    }

    /** §3.1 Request presentation of an HU at a workplace. */
    @PostMapping("/requests")
    public ResponseEntity<InductionEntryView> request(
            @Valid @RequestBody InductionRequest request,
            @RequestHeader(value = "X-Auth-User", required = false) String actor) {
        InductionEntryView view = service.request(request, actor == null ? "system" : actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    /** §3.2 Read a workplace's queue slice ({REQUESTED, IN_TRANSIT, QUEUED}); DONE excluded. */
    @GetMapping("/queue")
    public List<InductionEntryView> queue(
            @RequestParam("workplaceId") UUID workplaceId,
            @RequestParam(value = "status", required = false) String status) {
        return service.readQueue(workplaceId, status);
    }

    /** §3.3 Mark an entry DONE (operator-driven). Idempotent. */
    @PostMapping("/entries/{entryId}/done")
    public InductionEntryView done(
            @PathVariable UUID entryId,
            @RequestHeader(value = "X-Auth-User", required = false) String actor) {
        return service.markDone(entryId, null, actor == null ? "system" : actor);
    }

    /** Read a single induction entry by id. */
    @GetMapping("/entries/{entryId}")
    public InductionEntryView get(@PathVariable UUID entryId) {
        return service.get(entryId);
    }
}
