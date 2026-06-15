package org.openwcs.orders.service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.openwcs.orders.api.DemoDashboardSeedResult;
import org.openwcs.orders.client.MasterDataClient;
import org.openwcs.orders.domain.LineStatus;
import org.openwcs.orders.domain.OrderLine;
import org.openwcs.orders.domain.OrderLineTransaction;
import org.openwcs.orders.domain.OrderStatus;
import org.openwcs.orders.domain.OrderType;
import org.openwcs.orders.domain.OutboundOrder;
import org.openwcs.orders.domain.TransactionType;
import org.openwcs.orders.repo.OutboundOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Demo DASHBOARD seeding (build.md §4.8). Backfills a representative spread of INBOUND + OUTBOUND
 * orders, lines and stock-posting transactions with BACKDATED timestamps over the trailing ~90 days
 * so the order reports light up on a fresh demo box: order-flow, /reports/dashboard,
 * /reports/dispatch and /reports/sla.
 *
 * <p>Gated to demo mode: requires demo SKUs to exist (the master-data demo catalog), exactly like
 * the existing "Add 10" seeder, and is a no-op-with-error (409) otherwise. Strictly demo-only — has
 * no effect when demo mode is off.
 *
 * <p>{@code created_at}/{@code updated_at}/{@code posted_at} are Hibernate-managed
 * (@CreationTimestamp / @UpdateTimestamp), so after persisting each order the timestamps are
 * rewritten with native UPDATEs to land them in the past.
 */
@Service
public class DemoDashboardSeedService {

    private static final Logger log = LoggerFactory.getLogger(DemoDashboardSeedService.class);

    private static final String[] ROUTES = {
        "CENTRAL_LONDON", "NORTH_REGION", "SOUTH_REGION", "EXPRESS_HUB", "CONTINENTAL",
    };
    private static final String[] SERVICES = {"STANDARD", "EXPRESS"};
    private static final long RNG_SEED = 20260615L;

    private final OutboundOrderRepository orders;
    private final MasterDataClient masterData;
    private final JdbcTemplate jdbc;

    public DemoDashboardSeedService(OutboundOrderRepository orders, MasterDataClient masterData,
            JdbcTemplate jdbc) {
        this.orders = orders;
        this.masterData = masterData;
        this.jdbc = jdbc;
    }

    /**
     * Backfill dashboard-relevant orders for a warehouse.
     *
     * @throws IllegalStateException if the demo catalog is empty (demo mode not enabled)
     */
    @Transactional
    public DemoDashboardSeedResult seed(UUID warehouseId) {
        List<UUID> demoSkus = masterData.listDemoSkus();
        if (demoSkus.isEmpty()) {
            throw new IllegalStateException("Demo mode is not enabled (no demo SKUs to build dashboard orders from).");
        }
        Random rnd = new Random(RNG_SEED);
        Instant now = Instant.now();
        int orderCount = 0;
        int lineCount = 0;
        int txnCount = 0;

        // 1) Shipped OUTBOUND orders spread over the last 90 days — SLA (on-time vs late) + flow
        //    completion + dispatch history. Roughly 80% on-time, 20% late vs the dispatch cut-off.
        for (int i = 0; i < 40; i++) {
            int daysAgo = 1 + rnd.nextInt(89);
            Instant created = now.minus(daysAgo, ChronoUnit.DAYS).minus(rnd.nextInt(8), ChronoUnit.HOURS);
            Instant dispatchBy = created.plus(1, ChronoUnit.DAYS);
            boolean late = rnd.nextInt(5) == 0; // ~20% late
            Instant shipped = late
                    ? dispatchBy.plus(2 + rnd.nextInt(20), ChronoUnit.HOURS)
                    : dispatchBy.minus(1 + rnd.nextInt(20), ChronoUnit.HOURS);

            OutboundOrder o = newOrder(warehouseId, OrderType.OUTBOUND, OrderStatus.SHIPPED, demoSkus, rnd);
            o.setDispatchBy(dispatchBy);
            o.setRouteCode(ROUTES[rnd.nextInt(ROUTES.length)]);
            o.setServiceCode(SERVICES[rnd.nextInt(SERVICES.length)]);
            for (OrderLine l : o.getLines()) {
                l.setStatus(LineStatus.ALLOCATED);
                l.setAllocatedQty(l.getQty());
                OrderLineTransaction txn = new OrderLineTransaction(l, TransactionType.PICK,
                        l.getQty(), null, null, null, null, "demo");
                l.addTransaction(txn);
                txnCount++;
            }
            OutboundOrder saved = orders.saveAndFlush(o);
            backdateOrder(saved, created, shipped);
            backdateLines(saved, created, shipped);
            backdateTxns(saved, shipped);
            orderCount++;
            lineCount += saved.getLines().size();
        }

        // 2) Open OUTBOUND orders due soon across statuses — dispatch board (route groups, next
        //    departure, ready vs assigned) + live dashboard "open" / "released today" / "shorts".
        OrderStatus[] openStatuses = {OrderStatus.CREATED, OrderStatus.RELEASED,
            OrderStatus.PARTIALLY_ALLOCATED, OrderStatus.ALLOCATED, OrderStatus.NOT_FULFILLABLE};
        for (int i = 0; i < 25; i++) {
            OrderStatus st = openStatuses[rnd.nextInt(openStatuses.length)];
            Instant created = now.minus(rnd.nextInt(48), ChronoUnit.HOURS);
            Instant updated = st == OrderStatus.CREATED ? created : now.minus(rnd.nextInt(6), ChronoUnit.HOURS);
            Instant dispatchBy = now.plus(2 + rnd.nextInt(48), ChronoUnit.HOURS);

            OutboundOrder o = newOrder(warehouseId, OrderType.OUTBOUND, st, demoSkus, rnd);
            o.setDispatchBy(dispatchBy);
            o.setRouteCode(ROUTES[rnd.nextInt(ROUTES.length)]);
            o.setServiceCode(SERVICES[rnd.nextInt(SERVICES.length)]);
            // A short line for PARTIALLY_ALLOCATED orders so today's "shorts" figure is non-zero.
            if (st == OrderStatus.PARTIALLY_ALLOCATED) {
                o.getLines().get(0).setStatus(LineStatus.SHORT);
            }
            OutboundOrder saved = orders.saveAndFlush(o);
            backdateOrder(saved, created, updated);
            backdateLines(saved, created, updated);
            orderCount++;
            lineCount += saved.getLines().size();
        }

        // 3) INBOUND receipts: some completed over the last 30 days (flow + dock activity) and some
        //    received TODAY with deliberate over/under postings so today's receive-errors figure
        //    (over-receipt, or under-receipt on a SHORT/CANCELLED line) is non-zero.
        for (int i = 0; i < 20; i++) {
            boolean today = i < 8;
            Instant created = today
                    ? now.minus(2 + rnd.nextInt(6), ChronoUnit.HOURS)
                    : now.minus(1 + rnd.nextInt(29), ChronoUnit.DAYS);
            Instant posted = created.plus(30 + rnd.nextInt(180), ChronoUnit.MINUTES);

            OutboundOrder o = newOrder(warehouseId, OrderType.INBOUND, OrderStatus.RELEASED, demoSkus, rnd);
            int errorKind = i % 4; // 0 over-receipt, 1 short under-receipt, others clean
            for (int li = 0; li < o.getLines().size(); li++) {
                OrderLine l = o.getLines().get(li);
                BigDecimal ordered = l.getQty();
                BigDecimal receivedQty = ordered;
                if (today && li == 0 && errorKind == 0) {
                    receivedQty = ordered.add(BigDecimal.valueOf(2 + rnd.nextInt(5))); // over-receipt
                } else if (today && li == 0 && errorKind == 1) {
                    receivedQty = ordered.subtract(BigDecimal.ONE.max(ordered.divide(BigDecimal.valueOf(2))));
                    l.setStatus(LineStatus.SHORT); // closed-short under-receipt
                }
                OrderLineTransaction txn = new OrderLineTransaction(l, TransactionType.RECEIPT,
                        receivedQty, null, null, null, null, "demo");
                l.addTransaction(txn);
                txnCount++;
            }
            OutboundOrder saved = orders.saveAndFlush(o);
            backdateOrder(saved, created, posted);
            backdateLines(saved, created, posted);
            backdateTxns(saved, posted);
            orderCount++;
            lineCount += saved.getLines().size();
        }

        // 4) A handful of open INBOUND expected-today orders (no postings yet) for the "expected
        //    today" / put-away backlog figures.
        for (int i = 0; i < 6; i++) {
            Instant created = now.minus(rnd.nextInt(8), ChronoUnit.HOURS);
            OutboundOrder o = newOrder(warehouseId, OrderType.INBOUND, OrderStatus.CREATED, demoSkus, rnd);
            OutboundOrder saved = orders.saveAndFlush(o);
            backdateOrder(saved, created, created);
            backdateLines(saved, created, created);
            orderCount++;
            lineCount += saved.getLines().size();
        }

        log.info("demo dashboard seed for warehouse {}: backfilled {} orders, {} lines, {} transactions"
                        + " (backdated up to 90 days) because an admin requested dashboard demo data",
                warehouseId, orderCount, lineCount, txnCount);
        return new DemoDashboardSeedResult(orderCount, lineCount, txnCount);
    }

    private OutboundOrder newOrder(UUID warehouseId, OrderType type, OrderStatus status,
            List<UUID> demoSkus, Random rnd) {
        OutboundOrder o = new OutboundOrder();
        o.setOrderRef("DEMO-DASH-" + type + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        o.setOrderType(type);
        o.setWarehouseId(warehouseId);
        o.setCustomerRef("DEMO");
        o.setStatus(status);
        o.setPriority(type == OrderType.OUTBOUND ? 1 + rnd.nextInt(10) : 5);

        int lineCount = type == OrderType.INBOUND ? 1 + rnd.nextInt(3) : 1 + rnd.nextInt(2);
        for (int n = 0; n < lineCount; n++) {
            OrderLine l = new OrderLine();
            l.setLineNo(n + 1);
            l.setSkuId(demoSkus.get(rnd.nextInt(demoSkus.size())));
            long qty = type == OrderType.INBOUND ? 10L + rnd.nextInt(91) : 1L + rnd.nextInt(4);
            l.setQty(BigDecimal.valueOf(qty));
            o.addLine(l);
        }
        return o;
    }

    private void backdateOrder(OutboundOrder o, Instant created, Instant updated) {
        jdbc.update("update orders.outbound_order set created_at = ?, updated_at = ? where order_id = ?",
                Timestamp.from(created), Timestamp.from(updated), o.getId());
    }

    private void backdateLines(OutboundOrder o, Instant created, Instant updated) {
        jdbc.update("update orders.order_line set created_at = ?, updated_at = ? where order_id = ?",
                Timestamp.from(created), Timestamp.from(updated), o.getId());
    }

    private void backdateTxns(OutboundOrder o, Instant posted) {
        jdbc.update("update orders.order_line_transaction t set posted_at = ?"
                        + " from orders.order_line l where t.line_id = l.line_id and l.order_id = ?",
                Timestamp.from(posted), o.getId());
    }
}
