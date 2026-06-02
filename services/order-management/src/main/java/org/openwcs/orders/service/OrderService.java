package org.openwcs.orders.service;

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
import org.openwcs.orders.api.PostTransactionRequest;
import org.openwcs.orders.client.AllocationClient;
import org.openwcs.orders.domain.OrderLine;
import org.openwcs.orders.domain.OrderLineTransaction;
import org.openwcs.orders.domain.OrderOutboxMessage;
import org.openwcs.orders.domain.OrderStatus;
import org.openwcs.orders.domain.OrderType;
import org.openwcs.orders.domain.OutboundOrder;
import org.openwcs.orders.domain.TransactionType;
import org.openwcs.orders.repo.OrderOutboxRepository;
import org.openwcs.orders.repo.OutboundOrderRepository;
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

    private final OutboundOrderRepository orders;
    private final AllocationClient allocation;
    private final OrderOutboxRepository outbox;

    public OrderService(OutboundOrderRepository orders, AllocationClient allocation, OrderOutboxRepository outbox) {
        this.orders = orders;
        this.allocation = allocation;
        this.outbox = outbox;
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
        order.setStatus(OrderStatus.CREATED);

        int lineNo = 1;
        for (CreateOrderRequest.Line line : request.lines()) {
            OrderLine orderLine = new OrderLine();
            orderLine.setLineNo(lineNo++);
            orderLine.setSkuId(line.skuId());
            orderLine.setQty(line.qty());
            order.addLine(orderLine);
        }
        return OrderView.from(orders.save(order));
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

    /** Most-urgent-first queue of orders awaiting release (CREATED or NOT_FULFILLABLE). */
    @Transactional(readOnly = true)
    public List<OrderView> releaseQueue(UUID warehouseId) {
        return orders.releaseQueue(warehouseId, List.of(OrderStatus.CREATED, OrderStatus.NOT_FULFILLABLE))
                .stream().map(OrderView::from).toList();
    }

    /** Release all CREATED orders due by the cut-off, most urgent first. */
    @Transactional
    public List<OrderView> releaseDue(UUID warehouseId, Instant cutoff) {
        return orders.dueForRelease(warehouseId, cutoff).stream()
                .map(this::releaseOrder)
                .map(OrderView::from)
                .toList();
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
        if (order.getStatus() != OrderStatus.CREATED) {
            allocation.cancel(order.getOrderRef());
        }
        order.setStatus(OrderStatus.CANCELLED);
        return OrderView.from(order);
    }

    @Transactional
    public OrderView ship(UUID id) {
        OutboundOrder order = require(id);
        if (order.getStatus() != OrderStatus.ALLOCATED) {
            throw new IllegalOrderStateException("Only ALLOCATED orders can ship (was " + order.getStatus() + ")");
        }
        order.setStatus(OrderStatus.SHIPPED);
        return OrderView.from(order);
    }

    /**
     * Post a stock transaction against a line: append the matching event to the
     * transaction log (correlation = order, stream = line) and record it locally. The
     * physical stock change is applied by the inventory projection.
     */
    @Transactional
    public OrderView postTransaction(UUID orderId, int lineNo, PostTransactionRequest request) {
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
                request.locationId(), request.huId(), request.batchId(), null, request.actor());
        line.addTransaction(txn);
        orders.flush(); // assign the transaction id for the outbox foreign key
        outbox.save(new OrderOutboxMessage(
                txn.getId(), line.getId().toString(), eventTypeFor(txnType),
                order.getId(), request.actor(), payload));
        return OrderView.from(order);
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
        if (order.getStatus() != OrderStatus.CREATED && order.getStatus() != OrderStatus.NOT_FULFILLABLE) {
            throw new IllegalOrderStateException(
                    "Only CREATED or NOT_FULFILLABLE orders can be released (was " + order.getStatus() + ")");
        }
        List<AllocationClient.Line> lines = order.getLines().stream()
                .map(l -> new AllocationClient.Line(l.getLineNo(), l.getSkuId(), l.getQty()))
                .toList();
        AllocationClient.AllocationResult result =
                allocation.allocate(order.getOrderRef(), order.getWarehouseId(), lines);
        order.setStatus(result.fulfillable() ? OrderStatus.ALLOCATED : OrderStatus.NOT_FULFILLABLE);
        return order;
    }

    private OutboundOrder require(UUID id) {
        return orders.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
    }
}
