package org.openwcs.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.orders.api.CreateOrderRequest;
import org.openwcs.orders.api.DispatchReport;
import org.openwcs.orders.api.OrderDashboardReport;
import org.openwcs.orders.api.OrderView;
import org.openwcs.orders.api.PostTransactionRequest;
import org.openwcs.orders.client.AllocationClient;
import org.openwcs.orders.service.OrderDashboardService;
import org.openwcs.orders.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Dashboard + dispatch aggregation against a real PostgreSQL 16: seeded orders across statuses,
 * routes and timestamps produce the documented open/today headline figures, the today per-hour
 * intake/throughput split, the rate-based projection, and the route grouping with next departure.
 */
@SpringBootTest
@Testcontainers
class OrderDashboardReportTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("openwcs.orders.relay.enabled", () -> "false");
    }

    @MockBean
    AllocationClient allocation;

    @MockBean
    org.openwcs.orders.client.MasterDataClient masterData;

    @Autowired
    OrderService orders;

    @Autowired
    OrderDashboardService dashboards;

    @Autowired
    JdbcTemplate jdbc;

    private OrderView create(UUID wh, String ref, String type, String qty) {
        return create(wh, ref, type, qty, null, null);
    }

    private OrderView create(UUID wh, String ref, String type, String qty, String route, Instant cutoff) {
        return orders.create(new CreateOrderRequest(
                ref, wh, type, null, null, cutoff, null, route, null, null,
                List.of(new CreateOrderRequest.Line(UUID.randomUUID(), new BigDecimal(qty)))));
    }

    private void post(UUID orderId, String qty) {
        orders.postTransaction(orderId, 1,
                new PostTransactionRequest(new BigDecimal(qty), UUID.randomUUID(), null, null,
                        "EACH", null, null),
                "tester");
    }

    @Test
    void dashboardSplitsInboundOutboundHeadlineAndPerHour() {
        UUID wh = UUID.randomUUID();
        when(allocation.allocate(any(), any(), anyList(), any(), anyBoolean())).thenAnswer(invocation ->
                new AllocationClient.AllocationResult("FULFILLABLE", null,
                        List.of(new AllocationClient.LineResult(1, new BigDecimal("5"), "ALLOCATED"))));

        // Inbound: one open (no receipt), one fully received today (completed).
        create(wh, "IN-OPEN", "INBOUND", "10");
        OrderView inDone = create(wh, "IN-DONE", "INBOUND", "5");
        post(inDone.id(), "5");

        // Outbound: created (open, not released), released+picked, shorted, shipped today.
        create(wh, "OUT-CREATED", "OUTBOUND", "5");
        OrderView picking = create(wh, "OUT-PICK", "OUTBOUND", "5");
        orders.release(picking.id());
        post(picking.id(), "2");
        OrderView shortOrder = create(wh, "OUT-SHORT", "OUTBOUND", "5");
        orders.release(shortOrder.id()); // ALLOCATED, then mark a line SHORT to simulate a shortfall
        jdbc.update("update orders.order_line set status = 'SHORT', updated_at = now()"
                + " where order_id = ?", shortOrder.id());
        OrderView shipped = create(wh, "OUT-SHIP", "OUTBOUND", "5");
        orders.release(shipped.id());
        orders.ship(shipped.id(), "tester");

        OrderDashboardReport d = dashboards.dashboard(wh);

        // Inbound headline.
        assertThat(d.inbound().open()).isEqualTo(1);          // IN-OPEN still missing stock
        assertThat(d.inbound().expectedToday()).isEqualTo(2); // both created today
        assertThat(d.inbound().projectedFinishIso()).isNotNull(); // 1 open, 1 completed today

        // Outbound headline.
        // open = CREATED + PICK(released) + SHORT(not_fulfillable); SHIP is shipped -> not open.
        assertThat(d.outbound().open()).isEqualTo(3);
        assertThat(d.outbound().releasedToday()).isEqualTo(3); // PICK, SHORT, SHIP all left CREATED
        assertThat(d.outbound().linesPickedToday()).isEqualTo(1); // only OUT-PICK posted a pick
        assertThat(d.outbound().shortsToday()).isEqualTo(1);   // OUT-SHORT line went SHORT
        assertThat(d.outbound().projectedFinishIso()).isNotNull(); // 3 open, 1 shipped today

        // Per-hour split: 24 buckets, inbound receipts and outbound picks each total to expected.
        assertThat(d.perHour()).hasSize(24);
        long inboundReceipts = d.perHour().stream().mapToLong(OrderDashboardReport.HourSplit::inbound).sum();
        long outboundPicks = d.perHour().stream().mapToLong(OrderDashboardReport.HourSplit::outbound).sum();
        assertThat(inboundReceipts).isEqualTo(1); // IN-DONE's receipt
        assertThat(outboundPicks).isEqualTo(1);   // OUT-PICK's pick
    }

    @Test
    void dashboardEmptyWarehouseDegradesToZerosAndNullProjection() {
        OrderDashboardReport d = dashboards.dashboard(UUID.randomUUID());
        assertThat(d.inbound().open()).isZero();
        assertThat(d.inbound().projectedFinishIso()).isNull();
        assertThat(d.outbound().open()).isZero();
        assertThat(d.outbound().projectedFinishIso()).isNull();
        assertThat(d.perHour()).hasSize(24);
    }

    @Test
    void dispatchGroupsOpenOutboundByRouteAndCutoff() {
        UUID wh = UUID.randomUUID();
        when(allocation.allocate(any(), any(), anyList(), any(), anyBoolean())).thenAnswer(invocation ->
                new AllocationClient.AllocationResult("FULFILLABLE", null,
                        List.of(new AllocationClient.LineResult(1, new BigDecimal("5"), "ALLOCATED"))));
        when(masterData.routeExists(any())).thenReturn(true);

        Instant soon = Instant.now().plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        Instant later = Instant.now().plus(6, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

        // Route ALPHA, soon cut-off: 2 orders, one allocated (ready).
        create(wh, "A-1", "OUTBOUND", "5", "ALPHA", soon);
        OrderView a2 = create(wh, "A-2", "OUTBOUND", "5", "ALPHA", soon);
        orders.release(a2.id()); // -> ALLOCATED (ready)
        // Route BETA, later cut-off: 1 open order, not ready.
        create(wh, "B-1", "OUTBOUND", "5", "BETA", later);

        DispatchReport dr = dashboards.dispatch(wh);

        assertThat(dr.routes()).hasSize(2);
        DispatchReport.Route alpha = dr.routes().get(0); // sorted by cut-off asc
        assertThat(alpha.routeCode()).isEqualTo("ALPHA");
        assertThat(alpha.cutoffIso()).isEqualTo(soon.toString());
        assertThat(alpha.ordersAssigned()).isEqualTo(2);
        assertThat(alpha.ordersReady()).isEqualTo(1);
        assertThat(alpha.status()).isEqualTo("loading"); // at least one ready

        DispatchReport.Route beta = dr.routes().get(1);
        assertThat(beta.routeCode()).isEqualTo("BETA");
        assertThat(beta.ordersReady()).isZero();
        assertThat(beta.status()).isEqualTo("open");

        assertThat(dr.nextDeparture()).isNotNull();
        assertThat(dr.nextDeparture().routeCode()).isEqualTo("ALPHA");
        assertThat(dr.nextDeparture().cutoffIso()).isEqualTo(soon.toString());
        assertThat(dr.nextDeparture().secondsToCutoff()).isPositive();
    }

    @Test
    void dispatchNullNextDepartureWhenNoOpenCutoffs() {
        DispatchReport dr = dashboards.dispatch(UUID.randomUUID());
        assertThat(dr.routes()).isEmpty();
        assertThat(dr.nextDeparture()).isNull();
    }
}
