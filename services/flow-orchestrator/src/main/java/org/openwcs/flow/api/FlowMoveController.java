package org.openwcs.flow.api;

import jakarta.validation.Valid;
import org.openwcs.flow.service.FlowMoveService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Generic physical-move dispatch (execution-layer item 1): {@code POST /api/flow/moves} moves a
 * handling unit from one location to another and returns the dispatched device-task id. Coarse RBAC
 * is enforced by {@code RbacFilter} (a write is DEVICE_OPERATE, the same level induction dispatch
 * uses); the actor comes from the gateway {@code X-Auth-User} header for the audit trail.
 */
@RestController
@RequestMapping("/api/flow/moves")
public class FlowMoveController {

    private final FlowMoveService service;

    public FlowMoveController(FlowMoveService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<FlowMoveResult> move(
            @Valid @RequestBody FlowMoveRequest request,
            @RequestHeader(value = "X-Auth-User", required = false) String actor) {
        FlowMoveResult result = service.move(request, actor == null ? "system" : actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }
}
