package org.openwcs.orders.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.openwcs.orders.api.CreateOrderRequest;
import org.openwcs.orders.api.IllegalOrderStateException;
import org.openwcs.orders.api.OrderNotFoundException;
import org.openwcs.orders.api.OrderView;
import org.openwcs.orders.api.PageResponse;
import org.openwcs.orders.api.ConfirmPickRequest;
import org.openwcs.orders.api.PickTaskView;
import org.openwcs.orders.api.PostTransactionRequest;
import org.openwcs.orders.client.AllocationClient;
import org.openwcs.orders.client.MasterDataClient;
import org.openwcs.orders.domain.LineStatus;
import org.openwcs.orders.domain.OrderLine;
import org.openwcs.orders.domain.OrderLineTransaction;
import org.openwcs.orders.domain.OrderOutboxMessage;
import org.openwcs.orders.domain.OrderStatus;
import org.openwcs.orders.domain.OrderType;
import org.openwcs.orders.domain.OutboundOrder;
import org.openwcs.orders.domain.TransactionType;
import org.openwcs.orders.repo.OrderLineRepository;
import org.openwcs.orders.repo.OrderOutboxRepository;
import org.openwcs.orders.repo.OutboundOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Order lifecycle, release management, and stock-transaction posting (build.md §4.6, §7;
 * ADR 0002). OUTBOUND release delegates allocation + cubing to the allocation service;
 * every order type posts stock transactions beneath its lines (receipts / picks / counts /
 * adjustments), appended to the transaction log and applied by the inventory projection.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OutboundOrderRepository orders;
    private final OrderLineRepository lines;
    private final AllocationClient allocation;
    private final OrderOutboxRepository outbox;
    private final MasterDataClient masterData;

    public OrderService(OutboundOrderRepository orders, OrderLineRepository lines, AllocationClient allocation,
                        OrderOutboxRepository outbox, MasterDataClient masterData) {
        this.orders = orders;
        this.lines = lines;
        this.allocation = allocation;
        this.outbox = outbox;
        this.masterData = masterData;
    }

    @Transactional
    public OrderView create(CreateOrderRequest request) {
        orders.findByOrderRef(request.orderRef()).ifPresent(o -> {
            throw new IllegalOrderStateException("Order already exists: " + request.orderRef());
        });
        OutboundOrder order = new OutboundOrder();
        order.setOrderRef(request.orderRef());
        order.setOrderType(request.orderType() == null ? OrderType.OUTBOUND : OrderType.valueOf(request.orderType()));
        order.setWarehouseId(request.warehouseId());
        order.setCustomerRef(request.customerRef());
        order.setPriority(request.priority() == null ? 0 : request.priority());
        order.setDispatchBy(request.dispatchBy());
        // Dispatch service + route reference master-data catalogs (routes are host-fed).
        if (request.serviceCode() != null && !masterData.shippingServiceExists(request.serviceCode())) {
            throw new IllegalArgumentException("Unknown shipping service: " + request.serviceCode());
        }
        if (request.routeCode() != null && !masterData.routeExists(request.routeCode())) {
            throw new IllegalArgumentException("Unknown route: " + request.routeCode());
        }
        if (request.labelTemplateCode() != null && !masterData.labelTemplateExists(request.labelTemplateCode())) {
            throw new IllegalArgumentException("Unknown label template: " + request.labelTemplateCode());
        }
        order.setServiceCode(request.serviceCode());
        order.setRouteCode(request.routeCode());
        order.setShipTo(request.shipTo());
        order.setLabelTemplateCode(request.labelTemplateCode());
        order.setStatus(OrderStatus.CREATED);

        int lineNo = 1;
        for (CreateOrderRequest.Line line : request.lines()) {
            OrderLine orderLine = new OrderLine();
            orderLine.setLineNo(lineNo++);
            orderLine.setSkuId(line.skuId());
            orderLine.setQty(line.qty());
            order.addLine(orderLine);
        }
        OutboundOrder saved = orders.save(order);
        log.info("order {} received: type {}, {} lines, priority {}, dispatch by {}",
                saved.getOrderRef(), saved.getOrderType(), saved.getLines().size(),
                saved.getPriority(), saved.getDispatchBy());
        return OrderView.from(saved);
    }

    @Transactional(readOnly = true)
    public OrderView get(UUID id) {
        return OrderView.from(require(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderView> list(UUID warehouseId, OrderStatus status, Pageable pageable) {
        Page<OutboundOrder> page = status != null
                ? orders.findByWarehouseIdAndStatus(warehouseId, status, pageable)
                : orders.findByWarehouseId(warehouseId, pageable);
        return PageResponse.of(page.map(OrderView::from));
    }

    /** Release one order: delegate allocation; ALLOCATED on success, else NOT_FULFILLABLE. */
    @Transactional
    public OrderView release(UUID id) {
        return OrderView.from(releaseOrder(require(id)));
    }

    /**
     * Short allocate and release: the explicit supervisor decision to work a short order with
     * whatever stock is available. Re-runs allocation in allow-short mode — the available qty
     * per line is reserved (zero-stock lines allocate nothing), only the allocated quantities
     * are cubed, and the order is released as PARTIALLY_ALLOCATED so it picks and ships short.
     * {@code actor} is the deciding user, recorded for audit.
     */
    @Transactional
    public OrderView releaseShort(UUID id, String actor) {
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("actor is required to short release an order (audit)");
        }
        OutboundOrder order = require(id);
        if (order.getOrderType() != OrderType.OUTBOUND) {
            throw new IllegalOrderStateException("Only OUTBOUND orders are released/allocated");
        }
        if (order.getStatus() != OrderStatus.NOT_FULFILLABLE) {
            throw new IllegalOrderStateException(
                    "Only NOT_FULFILLABLE orders can be short released (was " + order.getStatus() + ")");
        }
        AllocationClient.AllocationResult result = callAllocation(order, true);
        if (result.fulfillable()) {
            // Stock arrived between the short decision and the re-run: nothing is short after all.
            applyLineResults(order, result);
            order.setStatus(OrderStatus.ALLOCATED);
            order.setStatusDetail(null);
            log.info("order {} short release by {} found full stock: all {} lines allocated, status ALLOCATED",
                    order.getOrderRef(), actor, order.getLines().size());
        } else if (result.shortFulfillable()) {
            applyLineResults(order, result);
            order.setStatus(OrderStatus.PARTIALLY_ALLOCATED);
            order.setStatusDetail(shortReleaseDetail(actor, result.detail()));
            log.info("order {} short released by {}: {} — available qty reserved, order will pick and"
                            + " ship short (PARTIALLY_ALLOCATED)",
                    order.getOrderRef(), actor, result.detail());
        } else if (result.cubingFailed()) {
            order.setStatus(OrderStatus.CUBING_FAILED);
            order.setStatusDetail(result.detail());
            log.warn("order {} short release by {} parked in CUBING_FAILED: {}",
                    order.getOrderRef(), actor, result.detail());
        } else {
            // Nothing at all is available — there is nothing to pick, so refuse the short release.
            throw new IllegalOrderStateException("Cannot short release " + order.getOrderRef()
                    + ": no stock is available for any line, nothing would be picked");
        }
        return OrderView.from(order);
    }

    /** Status detail shown on a short-released order: the decision (who) + the shortfall summary. */
    private static String shortReleaseDetail(String actor, String allocationDetail) {
        String summary = allocationDetail == null ? "Short allocated" : allocationDetail;
        return summary + " (short released by " + actor + ")";
    }

    /** Copy the per-line allocation outcome onto the order lines (allocated qty + ALLOCATED/SHORT). */
    private static void applyLineResults(OutboundOrder order, AllocationClient.AllocationResult result) {
        for (AllocationClient.LineResult lineResult : result.lines()) {
            order.getLines().stream()
                    .filter(l -> l.getLineNo() == lineResult.lineNo())
                    .findFirst()
                    .ifPresent(line -> {
                        line.setAllocatedQty(lineResult.allocatedQty());
                        line.setStatus("SHORT".equals(lineResult.status())
                                ? LineStatus.SHORT : LineStatus.ALLOCATED);
                        // Thread the allocated primary pick location onto the line (best-effort:
                        // older allocation paths supply none, leaving it null — no regression).
                        if (lineResult.pickLocationId() != null) {
                            line.setPickLocationId(lineResult.pickLocationId());
                        }
                    });
        }
    }

    /** Most-urgent-first queue of orders awaiting release (CREATED or NOT_FULFILLABLE). */
    @Transactional(readOnly = true)
    public List<OrderView> releaseQueue(UUID warehouseId) {
        return orders.releaseQueue(warehouseId, List.of(OrderStatus.CREATED, OrderStatus.NOT_FULFILLABLE))
                .stream().map(OrderView::from).toList();
    }

    /** Release all CREATED orders due by the cut-off, most urgent first. */
    @Transactional
    public List<OrderView> releaseDue(UUID warehouseId, Instant cutoff) {
        List<OrderView> released = orders.dueForRelease(warehouseId, cutoff).stream()
                .map(this::releaseOrder)
                .map(OrderView::from)
                .toList();
        log.info("release-due pass for warehouse {}: {} orders due by {} processed", warehouseId,
                released.size(), cutoff);
        return released;
    }

    @Transactional
    public OrderView cancel(UUID id) {
        OutboundOrder order = require(id);
        if (order.getStatus() == OrderStatus.SHIPPED) {
            throw new IllegalOrderStateException("Cannot cancel a shipped order");
        }
        if (order.getStatus() == OrderStatus.CANCELLED) {
            return OrderView.from(order);
        }
        // Release any held reservations via the allocation service (no-op for a CREATED
        // order that was never released).
        OrderStatus previous = order.getStatus();
        if (order.getStatus() != OrderStatus.CREATED) {
            allocation.cancel(order.getOrderRef());
        }
        order.setStatus(OrderStatus.CANCELLED);
        log.info("order {} cancelled (was {}{})", order.getOrderRef(), previous,
                previous == OrderStatus.CREATED ? "" : "; held reservations released");
        return OrderView.from(order);
    }

    /**
     * Dispatch the order. A PARTIALLY_ALLOCATED (short-released) order ships short: the
     * OrderShipped confirmation reports the actually shipped qty per line against the ordered
     * qty, so the shortfall is visible to the host.
     */
    @Transactional
    public OrderView ship(UUID id, String actor) {
        OutboundOrder order = require(id);
        if (order.getStatus() != OrderStatus.ALLOCATED && order.getStatus() != OrderStatus.PARTIALLY_ALLOCATED) {
            throw new IllegalOrderStateException(
                    "Only ALLOCATED or PARTIALLY_ALLOCATED orders can ship (was " + order.getStatus() + ")");
        }
        OrderStatus previous = order.getStatus();
        boolean shortShipped = previous == OrderStatus.PARTIALLY_ALLOCATED;
        order.setStatus(OrderStatus.SHIPPED);
        // Stage the OrderShipped confirmation (per-line ordered vs shipped) on the outbox; the
        // relay appends it to the transaction log, where the host confirmation feed picks it up.
        outbox.save(new OrderOutboxMessage(null, order.getOrderRef(), "OrderShipped", order.getId(),
                actor == null || actor.isBlank() ? "system" : actor, shippedPayload(order, shortShipped)));
        log.info("order {} dispatched by {}: {} -> SHIPPED{}", order.getOrderRef(),
                actor == null || actor.isBlank() ? "system" : actor, previous,
                shortShipped ? " (short shipped: " + order.getStatusDetail() + ")" : "");
        return OrderView.from(order);
    }

    /** OrderShipped event payload: ordered vs actually shipped (allocated/picked) qty per line. */
    private static Map<String, Object> shippedPayload(OutboundOrder order, boolean shortShipped) {
        List<Map<String, Object>> lines = new java.util.ArrayList<>();
        for (OrderLine line : order.getLines()) {
            // Shipped = the allocated (reserved + picked) qty. Lines of a fully ALLOCATED order
            // released before per-line tracking carry allocatedQty 0; they shipped in full.
            java.math.BigDecimal shipped = line.getAllocatedQty().signum() > 0
                    ? line.getAllocatedQty()
                    : (shortShipped ? java.math.BigDecimal.ZERO : line.getQty());
            Map<String, Object> entry = new HashMap<>();
            entry.put("lineNo", line.getLineNo());
            entry.put("skuId", line.getSkuId());
            entry.put("orderedQty", line.getQty());
            entry.put("shippedQty", shipped);
            entry.put("shortQty", line.getQty().subtract(shipped));
            lines.add(entry);
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("warehouseId", order.getWarehouseId());
        payload.put("orderRef", order.getOrderRef());
        payload.put("shortShipped", shortShipped);
        payload.put("lines", lines);
        return payload;
    }

    /**
     * Post a stock transaction against a line: record it + an outbox row in one transaction
     * (the relay appends the event to the log). {@code actor} is the authenticated user
     * (gateway-forwarded) or the request fallback — required for audit.
     */
    @Transactional
    public OrderView postTransaction(UUID orderId, int lineNo, PostTransactionRequest request, String actor) {
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("actor is required to post a stock transaction (audit)");
        }
        OutboundOrder order = require(orderId);
        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new IllegalOrderStateException("Cannot post transactions to a cancelled order");
        }
        OrderLine line = order.getLines().stream()
                .filter(l -> l.getLineNo() == lineNo)
                .findFirst()
                .orElseThrow(() -> new IllegalOrderStateException(
                        "No line " + lineNo + " on order " + order.getOrderRef()));

        TransactionType txnType = transactionTypeFor(order.getOrderType());
        Map<String, Object> payload = buildPayload(order, line, txnType, request);

        // Record the transaction (event id filled in later by the relay) and the outbox row
        // in ONE local transaction, so the audit record can never be lost.
        OrderLineTransaction txn = new OrderLineTransaction(line, txnType, request.qty(),
                request.locationId(), request.huId(), request.batchId(), null, actor);
        line.addTransaction(txn);
        orders.flush(); // assign the transaction id for the outbox foreign key
        outbox.save(new OrderOutboxMessage(
                txn.getId(), line.getId().toString(), eventTypeFor(txnType),
                order.getId(), actor, payload));
        log.info("order {} line {}: {} posted by {} (sku {}, qty {}, location {}, hu {})",
                order.getOrderRef(), lineNo, txnType, actor, line.getSkuId(), request.qty(),
                request.locationId(), request.huId());
        return OrderView.from(order);
    }

    /**
     * Operator pick queue (build.md §7 outbound process): outbound order lines that are
     * released/allocated and still need picking, ordered for a sensible pick walk (most-urgent
     * order first, then line). Best-effort — fully picked lines drop out; the allocated pick
     * location is included when available (see {@link PickTaskView} for the location gap).
     */
    @Transactional(readOnly = true)
    public List<PickTaskView> pickTasks(UUID warehouseId) {
        List<PickTaskView> tasks = new java.util.ArrayList<>();
        for (OutboundOrder order : orders.pickQueue(warehouseId)) {
            for (OrderLine line : order.getLines()) {
                if (!isPickable(line)) {
                    continue;
                }
                PickTaskView task = PickTaskView.from(order, line);
                if (task.remainingQty().signum() > 0) {
                    tasks.add(task);
                }
            }
        }
        return tasks;
    }

    /**
     * Confirm an operator pick against a line: post a Picked stock transaction (reusing the
     * outbox→txlog posting that decrements stock) and advance the line's picked quantity. A
     * short confirm under the requested quantity marks the line SHORT. The pickedQty is the
     * just-picked increment; idempotent-ish in that a zero confirm posts nothing. {@code actor}
     * is the authenticated operator (gateway-forwarded) — required for audit. Returns the
     * updated pick-task row. 409 ({@link IllegalOrderStateException}) when the line is not
     * pickable (cancelled order, non-allocated line, or already fully picked).
     */
    @Transactional
    public PickTaskView confirmPick(UUID lineId, ConfirmPickRequest request, String actor) {
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("actor is required to confirm a pick (audit)");
        }
        OrderLine line = lines.findByIdWithOrder(lineId)
                .orElseThrow(() -> new IllegalOrderStateException("No pick task for line " + lineId));
        OutboundOrder order = line.getOrder();
        if (order.getOrderType() != OrderType.OUTBOUND) {
            throw new IllegalOrderStateException("Line " + lineId + " is not an outbound pick task");
        }
        if (!isPickable(line)) {
            throw new IllegalOrderStateException(
                    "Line " + lineId + " is not pickable (order " + order.getStatus()
                            + ", line " + line.getStatus() + ", picked "
                            + PickTaskView.pickedQtyOf(line) + " of " + PickTaskView.requestedQtyOf(line) + ")");
        }

        BigDecimal pickedQty = request.pickedQty();
        BigDecimal requested = PickTaskView.requestedQtyOf(line);
        BigDecimal alreadyPicked = PickTaskView.pickedQtyOf(line);
        if (pickedQty.signum() < 0) {
            throw new IllegalArgumentException("pickedQty must not be negative");
        }
        if (alreadyPicked.add(pickedQty).compareTo(requested) > 0) {
            throw new IllegalOrderStateException("Over-pick: picking " + pickedQty + " would exceed the requested "
                    + requested + " (already picked " + alreadyPicked + ") on line " + lineId);
        }

        // Post the Picked stock transaction (same outbox→txlog path as postTransaction): record
        // the line transaction + the outbox row in ONE local transaction so the audit is durable.
        UUID location = request.locationId() != null ? request.locationId() : PickTaskView.pickLocationOf(line);
        if (pickedQty.signum() > 0) {
            Map<String, Object> payload = pickPayload(order, line, pickedQty, location);
            OrderLineTransaction txn = new OrderLineTransaction(line, TransactionType.PICK, pickedQty,
                    location, null, null, null, actor);
            line.addTransaction(txn);
            orders.flush(); // assign the transaction id for the outbox foreign key
            outbox.save(new OrderOutboxMessage(
                    txn.getId(), line.getId().toString(), "Picked", order.getId(), actor, payload));
        }

        // Advance status: a short confirm under the requested qty marks the line SHORT.
        BigDecimal nowPicked = PickTaskView.pickedQtyOf(line);
        if (request.isShort() && nowPicked.compareTo(requested) < 0) {
            line.setStatus(LineStatus.SHORT);
        }
        log.info("pick confirmed on order {} line {} by {}: +{} (now {} of {}{})", order.getOrderRef(),
                line.getLineNo(), actor, pickedQty, nowPicked, requested, request.isShort() ? ", short" : "");
        return PickTaskView.from(order, line);
    }

    /** A line is pickable while its order is released/fulfillable, the line is allocated/short, and qty remains. */
    private static boolean isPickable(OrderLine line) {
        OutboundOrder order = line.getOrder();
        boolean orderOpen = order.getStatus() == OrderStatus.ALLOCATED
                || order.getStatus() == OrderStatus.PARTIALLY_ALLOCATED;
        boolean lineOpen = line.getStatus() == LineStatus.ALLOCATED || line.getStatus() == LineStatus.SHORT;
        boolean qtyRemains = PickTaskView.requestedQtyOf(line).subtract(PickTaskView.pickedQtyOf(line)).signum() > 0;
        return orderOpen && lineOpen && qtyRemains;
    }

    /** Picked event payload (mirrors {@link #buildPayload} for a PICK): carries qty (not signed). */
    private static Map<String, Object> pickPayload(OutboundOrder order, OrderLine line, BigDecimal qty, UUID location) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("warehouseId", order.getWarehouseId());
        payload.put("skuId", line.getSkuId());
        payload.put("locationId", location);
        payload.put("uomCode", "EACH");
        payload.put("qty", qty);
        return payload;
    }

    private static TransactionType transactionTypeFor(OrderType orderType) {
        return switch (orderType) {
            case INBOUND -> TransactionType.RECEIPT;
            case OUTBOUND -> TransactionType.PICK;
            case COUNT -> TransactionType.COUNT;
            case ADJUSTMENT -> TransactionType.ADJUSTMENT;
        };
    }

    private static String eventTypeFor(TransactionType txnType) {
        return switch (txnType) {
            case RECEIPT -> "GoodsReceived";
            case PICK -> "Picked";
            case COUNT, ADJUSTMENT -> "StockAdjusted";
        };
    }

    private static Map<String, Object> buildPayload(
            OutboundOrder order, OrderLine line, TransactionType txnType, PostTransactionRequest req) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("warehouseId", order.getWarehouseId());
        payload.put("skuId", line.getSkuId());
        payload.put("batchId", req.batchId());
        payload.put("locationId", req.locationId());
        payload.put("huId", req.huId());
        payload.put("status", req.status());
        payload.put("uomCode", req.uomCode() == null ? "EACH" : req.uomCode());
        // GoodsReceived/Picked carry `qty`; StockAdjusted carries a signed `qtyDelta`.
        if (txnType == TransactionType.RECEIPT || txnType == TransactionType.PICK) {
            payload.put("qty", req.qty());
        } else {
            payload.put("qtyDelta", req.qty());
        }
        return payload;
    }

    private OutboundOrder releaseOrder(OutboundOrder order) {
        if (order.getOrderType() != OrderType.OUTBOUND) {
            throw new IllegalOrderStateException("Only OUTBOUND orders are released/allocated");
        }
        if (order.getStatus() != OrderStatus.CREATED && order.getStatus() != OrderStatus.NOT_FULFILLABLE
                && order.getStatus() != OrderStatus.CUBING_FAILED) {
            throw new IllegalOrderStateException(
                    "Only CREATED, NOT_FULFILLABLE or CUBING_FAILED orders can be released (was "
                            + order.getStatus() + ")");
        }
        AllocationClient.AllocationResult result = callAllocation(order, false);
        if (result.fulfillable()) {
            applyLineResults(order, result);
            order.setStatus(OrderStatus.ALLOCATED);
            order.setStatusDetail(null);
            log.info("order {} released: all {} lines allocated, status ALLOCATED", order.getOrderRef(),
                    order.getLines().size());
        } else if (result.cubingFailed()) {
            // A SKU is larger than the biggest carton; surface the reason for the UI.
            order.setStatus(OrderStatus.CUBING_FAILED);
            order.setStatusDetail(result.detail());
            log.warn("order {} release parked in CUBING_FAILED: {} (an operator must resolve shipper sizes)",
                    order.getOrderRef(), result.detail());
        } else {
            order.setStatus(OrderStatus.NOT_FULFILLABLE);
            order.setStatusDetail(result.detail());
            log.warn("order {} release left NOT_FULFILLABLE: {} (eligible for re-release once stock arrives)",
                    order.getOrderRef(), result.detail());
        }
        return order;
    }

    /** Delegate allocation + cubing for the order (shared by release and short release). */
    private AllocationClient.AllocationResult callAllocation(OutboundOrder order, boolean allowShort) {
        List<AllocationClient.Line> lines = order.getLines().stream()
                .map(l -> new AllocationClient.Line(l.getLineNo(), l.getSkuId(), l.getQty()))
                .toList();
        // Effective dispatch-label template: order override -> service default -> warehouse default.
        String template = order.getLabelTemplateCode();
        if (template == null && order.getServiceCode() != null) {
            template = masterData.serviceLabelTemplate(order.getServiceCode());
        }
        if (template == null) {
            template = masterData.warehouseDefaultLabelTemplate(order.getWarehouseId());
        }
        AllocationClient.Dispatch dispatch = new AllocationClient.Dispatch(
                order.getShipTo(), order.getServiceCode(), order.getRouteCode(), template);
        return allocation.allocate(order.getOrderRef(), order.getWarehouseId(), lines, dispatch, allowShort);
    }

    private OutboundOrder require(UUID id) {
        return orders.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
    }
}
