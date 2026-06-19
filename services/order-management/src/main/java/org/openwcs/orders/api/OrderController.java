package org.openwcs.orders.api;

import jakarta.validation.Valid;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.openwcs.common.security.AccessControl;
import org.openwcs.common.security.Permission;
import org.openwcs.orders.domain.OrderStatus;
import org.openwcs.orders.service.OrderService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
import org.springframework.web.server.ResponseStatusException;

/**
 * Outbound order management (build.md §4.6). Each endpoint requires a coded permission
 * (build.md §4.8), checked against the gateway-forwarded {@code X-Auth-Roles} via
 * {@link AccessGuard} (no-op when security is disabled).
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private static final String ROLES = "X-Auth-Roles";
    private static final String WAREHOUSES = "X-Auth-Warehouses";

    private final OrderService service;
    private final AccessGuard guard;

    public OrderController(OrderService service, AccessGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    /** 403 if the caller is warehouse-scoped and the body targets a warehouse outside their set. */
    private static void requireWarehouse(String warehouses, UUID warehouseId) {
        if (!AccessControl.warehouseAllowed(warehouses, warehouseId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not permitted for this warehouse.");
        }
    }

    @PostMapping
    public ResponseEntity<OrderView> create(
            @RequestHeader(name = ROLES, required = false) String roles,
            @RequestHeader(name = WAREHOUSES, required = false) String warehouses,
            @Valid @RequestBody CreateOrderRequest request) {
        guard.require(roles, Permission.ORDER_CREATE);
        requireWarehouse(warehouses, request.warehouseId());
        OrderView order = service.create(request);
        return ResponseEntity.created(URI.create("/api/orders/" + order.id())).body(order);
    }

    @GetMapping("/{id}")
    public OrderView get(@RequestHeader(name = ROLES, required = false) String roles, @PathVariable UUID id) {
        guard.require(roles, Permission.ORDER_VIEW);
        return service.get(id);
    }

    /**
     * Resolve an order / ASN by its reference (barcode) within a warehouse, for handheld scan
     * verification (process-designer): an outbound picksheet barcode or an inbound ASN barcode is
     * the order's {@code orderRef}. Returns HTTP 200 with {@code found:false} and a null order when
     * there is no match (NOT 404) so a process flow can branch on the result. Read-only — requires
     * ORDER_VIEW, the same identity/RBAC as the other order GETs.
     */
    @GetMapping("/resolve")
    public ResolveOrderResult resolve(
            @RequestHeader(name = ROLES, required = false) String roles,
            @RequestParam UUID warehouseId,
            @RequestParam String ref) {
        guard.require(roles, Permission.ORDER_VIEW);
        return service.resolve(warehouseId, ref);
    }

    @GetMapping
    public PageResponse<OrderView> list(
            @RequestHeader(name = ROLES, required = false) String roles,
            @RequestParam UUID warehouseId,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        guard.require(roles, Permission.ORDER_VIEW);
        return service.list(warehouseId, status, PageRequest.of(page, size, Sort.by("createdAt").descending()));
    }

    /** Release management: most-urgent-first queue of orders awaiting release. */
    @GetMapping("/release-queue")
    public List<OrderView> releaseQueue(
            @RequestHeader(name = ROLES, required = false) String roles, @RequestParam UUID warehouseId) {
        guard.require(roles, Permission.ORDER_VIEW);
        return service.releaseQueue(warehouseId);
    }

    /** Release all CREATED orders due within the given window (default: already due). */
    @PostMapping("/release-due")
    public List<OrderView> releaseDue(
            @RequestHeader(name = ROLES, required = false) String roles,
            @RequestParam UUID warehouseId,
            @RequestParam(defaultValue = "0") long withinMinutes) {
        guard.require(roles, Permission.ORDER_RELEASE);
        return service.releaseDue(warehouseId, Instant.now().plus(Duration.ofMinutes(withinMinutes)));
    }

    @PostMapping("/{id}/release")
    public OrderView release(@RequestHeader(name = ROLES, required = false) String roles, @PathVariable UUID id) {
        guard.require(roles, Permission.ORDER_RELEASE);
        return service.release(id);
    }

    /**
     * Short allocate and release a NOT_FULFILLABLE order: the supervisor decision to pick the
     * available quantity and ship the order short. Requires ORDER_RELEASE (SUPERVISOR/ADMIN
     * in the shipped role catalog); the deciding user is taken from the gateway-forwarded
     * {@code X-Auth-User} and recorded for audit.
     */
    @PostMapping("/{id}/release-short")
    public OrderView releaseShort(
            @RequestHeader(name = ROLES, required = false) String roles,
            @RequestHeader(name = "X-Auth-User", required = false) String authUser,
            @PathVariable UUID id) {
        guard.require(roles, Permission.ORDER_RELEASE);
        return service.releaseShort(id, (authUser != null && !authUser.isBlank()) ? authUser : "system");
    }

    @PostMapping("/{id}/cancel")
    public OrderView cancel(@RequestHeader(name = ROLES, required = false) String roles, @PathVariable UUID id) {
        guard.require(roles, Permission.ORDER_CANCEL);
        return service.cancel(id);
    }

    @PostMapping("/{id}/ship")
    public OrderView ship(
            @RequestHeader(name = ROLES, required = false) String roles,
            @RequestHeader(name = "X-Auth-User", required = false) String authUser,
            @PathVariable UUID id) {
        guard.require(roles, Permission.ORDER_SHIP);
        return service.ship(id, authUser);
    }

    /**
     * Operator pick queue: outbound order lines that are released/allocated and still need
     * picking, ordered for a pick walk. Read-only — requires ORDER_VIEW.
     */
    @GetMapping("/pick-tasks")
    public List<PickTaskView> pickTasks(
            @RequestHeader(name = ROLES, required = false) String roles, @RequestParam UUID warehouseId) {
        guard.require(roles, Permission.ORDER_VIEW);
        return service.pickTasks(warehouseId);
    }

    /**
     * Confirm an operator pick against a pick-task line: posts a Picked stock transaction
     * (decrementing stock through the outbox→txlog path) and advances the line's picked
     * quantity, marking it SHORT on a short confirm. Requires ORDER_POST_TRANSACTION (held by
     * OPERATOR in the shipped role catalog — the picking write permission); the operator is
     * taken from the gateway-forwarded {@code X-Auth-User} and recorded for audit. 409 when
     * the line is not pickable. The driving handheld process instance is read from the request
     * body ({@code processInstanceId}), falling back to the {@code X-Process-Instance} header.
     */
    @PostMapping("/pick-tasks/{lineId}/confirm")
    public PickTaskView confirmPick(
            @PathVariable UUID lineId,
            @RequestHeader(name = "X-Auth-User", required = false) String authUser,
            @RequestHeader(name = "X-Process-Instance", required = false) String processInstance,
            @RequestHeader(name = ROLES, required = false) String roles,
            @Valid @RequestBody ConfirmPickRequest request) {
        guard.require(roles, Permission.ORDER_POST_TRANSACTION);
        ConfirmPickRequest effective = request.processInstanceId() != null || processInstance == null
                ? request
                : new ConfirmPickRequest(request.pickedQty(), request.shortPick(), request.locationId(),
                        processInstance);
        return service.confirmPick(lineId, effective, authUser);
    }

    /**
     * Post a stock transaction beneath a line (receipt / pick / count / adjustment by order
     * type). The actor recorded for audit is the gateway-forwarded authenticated user
     * ({@code X-Auth-User}); the request-body {@code actor} is only a fallback for direct calls.
     */
    @PostMapping("/{id}/lines/{lineNo}/transactions")
    public OrderView postTransaction(
            @PathVariable UUID id,
            @PathVariable int lineNo,
            @RequestHeader(name = "X-Auth-User", required = false) String authUser,
            @RequestHeader(name = ROLES, required = false) String roles,
            @Valid @RequestBody PostTransactionRequest request) {
        guard.require(roles, Permission.ORDER_POST_TRANSACTION);
        String actor = (authUser != null && !authUser.isBlank()) ? authUser : request.actor();
        return service.postTransaction(id, lineNo, request, actor);
    }
}
