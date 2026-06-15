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
import org.openwcs.orders.api.OrderView;
import org.openwcs.orders.api.SlaReport;
import org.openwcs.orders.client.AllocationClient;
import org.openwcs.orders.domain.RouteDispatch;
import org.openwcs.orders.domain.RouteDispatchStatus;
import org.openwcs.orders.repo.RouteDispatchRepository;
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
 * Persisted route_dispatch entity against a real PostgreSQL 16: assigning outbound orders to a
 * route opens an OPEN wave, releasing one moves it to LOADING, shipping all of them flips it to
 * DEPARTED with a real departedAt, and both reports read the persisted state — /reports/dispatch
 * reflects the wave status + timestamps, and /reports/sla on-time uses departedAt vs cut-off.
 * Mirrors {@link OrderDashboardReportTest} / {@link OrderSlaReportTest}.
 */
@SpringBootTest
@Testcontainers
class RouteDispatchEntityTest {

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
    RouteDispatchRepository dispatches;

    @Autowired
    JdbcTemplate jdbc;

    private OrderView create(UUID wh, String ref, String route, Instant cutoff) {
        return orders.create(new CreateOrderRequest(
                ref, wh, "OUTBOUND", null, null, cutoff, null, route, null, null,
                List.of(new CreateOrderRequest.Line(UUID.randomUUID(), new BigDecimal("5")))));
    }

    private void allocateFullyMocked() {
        when(allocation.allocate(any(), any(), anyList(), any(), anyBoolean())).thenAnswer(invocation ->
                new AllocationClient.AllocationResult("FULFILLABLE", null,
                        List.of(new AllocationClient.LineResult(1, new BigDecimal("5"), "ALLOCATED"))));
        when(masterData.routeExists(any())).thenReturn(true);
    }

    @Test
    void assigningOrdersToARouteOpensAnOpenWave() {
        UUID wh = UUID.randomUUID();
        allocateFullyMocked();
        Instant cutoff = Instant.now().plus(4, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

        create(wh, "A-1", "ALPHA", cutoff);
        create(wh, "A-2", "ALPHA", cutoff);

        RouteDispatch wave = dispatches
                .findByWarehouseIdAndRouteCodeAndCutoff(wh, "ALPHA", cutoff)
                .orElseThrow();
        assertThat(wave.getStatus()).isEqualTo(RouteDispatchStatus.OPEN);
        assertThat(wave.getOrdersAssigned()).isEqualTo(2);
        assertThat(wave.getOrdersReady()).isZero();
        assertThat(wave.getOpenedAt()).isNotNull();
        assertThat(wave.getFirstLoadedAt()).isNull();
        assertThat(wave.getDepartedAt()).isNull();
    }

    @Test
    void releasingAnOrderMovesTheWaveToLoading() {
        UUID wh = UUID.randomUUID();
        allocateFullyMocked();
        Instant cutoff = Instant.now().plus(4, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

        create(wh, "L-1", "BETA", cutoff);
        OrderView l2 = create(wh, "L-2", "BETA", cutoff);
        orders.release(l2.id()); // -> ALLOCATED (ready)

        RouteDispatch wave = dispatches
                .findByWarehouseIdAndRouteCodeAndCutoff(wh, "BETA", cutoff)
                .orElseThrow();
        assertThat(wave.getStatus()).isEqualTo(RouteDispatchStatus.LOADING);
        assertThat(wave.getOrdersAssigned()).isEqualTo(2);
        assertThat(wave.getOrdersReady()).isEqualTo(1);
        assertThat(wave.getFirstLoadedAt()).isNotNull();
        assertThat(wave.getDepartedAt()).isNull();
    }

    @Test
    void shippingAllAssignedOrdersFlipsTheWaveToDepartedWithDepartedAt() {
        UUID wh = UUID.randomUUID();
        allocateFullyMocked();
        Instant cutoff = Instant.now().plus(4, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

        OrderView o1 = create(wh, "D-1", "GAMMA", cutoff);
        OrderView o2 = create(wh, "D-2", "GAMMA", cutoff);
        orders.release(o1.id());
        orders.release(o2.id());

        Instant beforeShip = Instant.now().minusSeconds(1);
        orders.ship(o1.id(), "tester");
        RouteDispatch afterFirst = dispatches
                .findByWarehouseIdAndRouteCodeAndCutoff(wh, "GAMMA", cutoff).orElseThrow();
        // One of two shipped -> still loading, not yet departed.
        assertThat(afterFirst.getStatus()).isEqualTo(RouteDispatchStatus.LOADING);
        assertThat(afterFirst.getDepartedAt()).isNull();

        orders.ship(o2.id(), "tester");
        RouteDispatch departed = dispatches
                .findByWarehouseIdAndRouteCodeAndCutoff(wh, "GAMMA", cutoff).orElseThrow();
        assertThat(departed.getStatus()).isEqualTo(RouteDispatchStatus.DEPARTED);
        assertThat(departed.getOrdersAssigned()).isEqualTo(2);
        assertThat(departed.getOrdersReady()).isEqualTo(2);
        assertThat(departed.getDepartedAt()).isNotNull();
        assertThat(departed.getDepartedAt()).isAfter(beforeShip);
    }

    @Test
    void dispatchReportReflectsThePersistedStatusAndCounts() {
        UUID wh = UUID.randomUUID();
        allocateFullyMocked();
        Instant soon = Instant.now().plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        Instant later = Instant.now().plus(6, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

        // ALPHA / soon: 2 orders, one released (ready) -> loading.
        create(wh, "RPT-A-1", "ALPHA", soon);
        OrderView a2 = create(wh, "RPT-A-2", "ALPHA", soon);
        orders.release(a2.id());
        // BETA / later: one untouched order -> open.
        create(wh, "RPT-B-1", "BETA", later);

        DispatchReport dr = dashboards.dispatch(wh);

        assertThat(dr.routes()).hasSize(2);
        DispatchReport.Route alpha = dr.routes().get(0); // soonest cut-off first
        assertThat(alpha.routeCode()).isEqualTo("ALPHA");
        assertThat(alpha.cutoffIso()).isEqualTo(soon.toString());
        assertThat(alpha.ordersAssigned()).isEqualTo(2);
        assertThat(alpha.ordersReady()).isEqualTo(1);
        assertThat(alpha.status()).isEqualTo("loading");

        DispatchReport.Route beta = dr.routes().get(1);
        assertThat(beta.routeCode()).isEqualTo("BETA");
        assertThat(beta.status()).isEqualTo("open");

        assertThat(dr.nextDeparture()).isNotNull();
        assertThat(dr.nextDeparture().routeCode()).isEqualTo("ALPHA");
        assertThat(dr.nextDeparture().cutoffIso()).isEqualTo(soon.toString());
        assertThat(dr.nextDeparture().secondsToCutoff()).isPositive();
    }

    @Test
    void dispatchReportShowsDepartedWaveWithRealTimestamp() {
        UUID wh = UUID.randomUUID();
        allocateFullyMocked();
        Instant cutoff = Instant.now().plus(3, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

        OrderView o = create(wh, "S-1", "DELTA", cutoff);
        orders.release(o.id());
        orders.ship(o.id(), "tester");

        DispatchReport dr = dashboards.dispatch(wh);
        assertThat(dr.routes()).hasSize(1);
        assertThat(dr.routes().get(0).status()).isEqualTo("departed");
        // A departed wave is no longer a candidate next departure.
        assertThat(dr.nextDeparture()).isNull();

        RouteDispatch wave = dispatches
                .findByWarehouseIdAndRouteCodeAndCutoff(wh, "DELTA", cutoff).orElseThrow();
        assertThat(wave.getDepartedAt()).isNotNull();
    }

    @Test
    void slaOnTimeUsesWaveDepartedAtVsCutoff() {
        UUID wh = UUID.randomUUID();
        allocateFullyMocked();

        // Ship one order today on route ECHO, then pin the wave's departed_at AFTER the cut-off so
        // the order is late by the actual departure even though its own ship time would be on-time.
        Instant cutoff = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        OrderView o = create(wh, "E-1", "ECHO", cutoff);
        orders.release(o.id());
        orders.ship(o.id(), "tester");

        // Anchor created/updated to UTC noon today (independent of container TZ) and set the order
        // ship time BEFORE the cut-off (would read on-time on the shippedAt fallback). The cut-off
        // is set on BOTH the order and its wave so the report's join (rd.cutoff = o.dispatch_by)
        // still matches after we move the timestamps.
        jdbc.update("""
                update orders.outbound_order
                set updated_at  = (current_date at time zone 'UTC') + make_interval(hours => 12),
                    created_at  = (current_date at time zone 'UTC') + make_interval(hours => 12)
                                  - make_interval(mins => 30),
                    dispatch_by = (current_date at time zone 'UTC') + make_interval(hours => 13)
                where order_id = ?
                """, o.id());
        // Pin the wave's cut-off to match the order's and its actual departure 2 hours AFTER the
        // cut-off -> late by departed_at even though the order's own ship time (12:00) was on-time.
        jdbc.update("""
                update orders.route_dispatch
                set cutoff      = (current_date at time zone 'UTC') + make_interval(hours => 13),
                    departed_at = (current_date at time zone 'UTC') + make_interval(hours => 15)
                where warehouse_id = ? and route_code = 'ECHO'
                """, wh);

        SlaReport sla = dashboards.sla(wh, 30);
        // departed_at (15:00) is after the cut-off (13:00) -> 0% on-time, even though the order's
        // own ship time (12:00) was before the cut-off.
        assertThat(sla.onTimeToCutoffPctToday()).isEqualTo(0.0);
        assertThat(sla.orderCycleTimeMedianMinToday()).isNotNull();
    }

    @Test
    void slaOnTimeFallsBackToShipTimeWhenNoDepartedWave() {
        UUID wh = UUID.randomUUID();
        allocateFullyMocked();

        // Order with a route but the wave never departs (only one of two ships), so the SLA uses
        // the order's own ship time vs the cut-off.
        Instant cutoff = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        OrderView o1 = create(wh, "F-1", "FOXTROT", cutoff);
        create(wh, "F-2", "FOXTROT", cutoff); // keeps the wave from departing
        orders.release(o1.id());
        orders.ship(o1.id(), "tester");

        jdbc.update("""
                update orders.outbound_order
                set updated_at  = (current_date at time zone 'UTC') + make_interval(hours => 12),
                    created_at  = (current_date at time zone 'UTC') + make_interval(hours => 12)
                                  - make_interval(mins => 30),
                    dispatch_by = (current_date at time zone 'UTC') + make_interval(hours => 13)
                where order_id = ?
                """, o1.id());

        SlaReport sla = dashboards.sla(wh, 30);
        // No departed wave -> fall back to ship time (12:00) vs cut-off (13:00) -> on-time.
        assertThat(sla.onTimeToCutoffPctToday()).isEqualTo(100.0);
    }
}
