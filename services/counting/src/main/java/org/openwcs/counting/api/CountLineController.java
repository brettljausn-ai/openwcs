package org.openwcs.counting.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.openwcs.counting.service.ClaimNextResult;
import org.openwcs.counting.service.LineReservationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator-facing count-line reservation API for area-driven counting. An operator working a Master
 * Data Area claims the next eligible line (atomically, area-scoped) and releases its instance's
 * reservations on close. The acting identity is the gateway-forwarded {@code X-Auth-User}; the
 * {@code operator} body field is a fallback for direct calls. Gated by the same per-endpoint RBAC as
 * the other operator counting endpoints ({@link RbacFilter}).
 */
@RestController
@RequestMapping("/api/counting/lines")
public class CountLineController {

    private final LineReservationService reservations;

    public CountLineController(LineReservationService reservations) {
        this.reservations = reservations;
    }

    @PostMapping("/claim-next")
    public ClaimNextResult claimNext(@Valid @RequestBody ClaimNextRequest request,
                                     @RequestHeader(name = "X-Auth-User", required = false) String authUser) {
        return reservations.claimNext(
                request.warehouseId(), request.areaId(), request.instanceId(), actor(authUser, request.operator()));
    }

    @PostMapping("/release")
    public ReleaseResult release(@Valid @RequestBody ReleaseRequest request) {
        int freed = reservations.release(request.instanceId());
        return new ReleaseResult(freed);
    }

    private static String actor(String forwarded, String fallback) {
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded;
        }
        return fallback == null || fallback.isBlank() ? "system" : fallback;
    }

    /** Claim-next body: the warehouse, the area to count, and the holding instance (handheld/runtime). */
    public record ClaimNextRequest(
            @NotNull UUID warehouseId,
            @NotNull UUID areaId,
            @NotNull String instanceId,
            String operator) {
    }

    /** Release body: the holding instance whose unstarted reservations should be freed. */
    public record ReleaseRequest(@NotNull String instanceId) {
    }

    /** Release outcome: how many reservations were freed. */
    public record ReleaseResult(int freed) {
    }
}
