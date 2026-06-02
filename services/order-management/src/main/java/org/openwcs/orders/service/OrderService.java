package org.openwcs.orders.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.openwcs.orders.api.CreateOrderRequest;
import org.openwcs.orders.api.IllegalOrderStateException;
import org.openwcs.orders.api.OrderNotFoundException;
import org.openwcs.orders.api.OrderView;
import org.openwcs.orders.api.PageResponse;
import org.openwcs.orders.client.AllocationClient;
import org.openwcs.orders.domain.OrderLine;
import org.openwcs.orders.domain.OrderStatus;
import org.openwcs.orders.domain.OutboundOrder;
import org.openwcs.orders.repo.OutboundOrderRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbound order lifecycle + release management (build.md §4.6, §7; ADR 0002). Release
 * delegates pick-location allocation + cubing to the allocation service and records the
 * resulting fulfilment status; orders are released most-urgent-first by priority then
 * dispatch time.
 */
@Service
public class OrderService {

    private final OutboundOrderRepository orders;
    private final AllocationClient allocation;

    public OrderService(OutboundOrderRepository orders, AllocationClient allocation) {
        this.orders = orders;
        this.allocation = allocation;
    }

    @Transactional
    public OrderView create(CreateOrderRequest request) {
        orders.findByOrderRef(request.orderRef()).ifPresent(o -> {
            throw new IllegalOrderStateException("Order already exists: " + request.orderRef());
        });
        OutboundOrder order = new OutboundOrder();
        order.setOrderRef(request.orderRef());
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

    private OutboundOrder releaseOrder(OutboundOrder order) {
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
