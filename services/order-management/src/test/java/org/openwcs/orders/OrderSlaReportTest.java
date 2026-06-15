package org.openwcs.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.orders.api.CreateOrderRequest;
import org.openwcs.orders.api.OrderView;
import org.openwcs.orders.api.SlaReport;
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
 * SLA report against a real PostgreSQL 16: shipped outbound orders with ship times before/after
 * their cut-off and varied created -> shipped spans produce the documented on-time-to-cutoff
 * percent and the server-side cycle-time median, for today and per UTC day. Mirrors
 * {@link OrderDashboardReportTest} / {@link OrderFlowReportTest}.
 */
@SpringBootTest
@Testcontainers
class OrderSlaReportTest {

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

    private OrderView create(UUID wh, String ref) {
        return orders.create(new CreateOrderRequest(
                ref, wh, "OUTBOUND", null, null, null, null, null, null, null,
                List.of(new CreateOrderRequest.Line(UUID.randomUUID(), new BigDecimal("5")))));
    }

    /**
     * Ship an order, then pin its ship time (updated_at), created_at and cut-off (dispatch_by).
     * Ship time is noon UTC, {@code shipDaysAgo} days back, so it stays inside that UTC day
     * regardless of the wall-clock run time. created = ship - cycleMinutes; cut-off = ship +
     * cutoffOffsetMinutes (positive => shipped before cut-off => on-time).
     */
    private void shipWithTimes(UUID wh, String ref, int cycleMinutes, int cutoffOffsetMinutes,
                               int shipDaysAgo) {
        OrderView o = create(wh, ref);
        orders.release(o.id());
        orders.ship(o.id(), "tester");
        // Anchor everything to UTC noon so day-bucketing (which uses `at time zone 'UTC'`) is
        // independent of the session/container timezone.
        jdbc.update("""
                update orders.outbound_order
                set updated_at  = ((current_date at time zone 'UTC') - make_interval(days => ?))
                                  + make_interval(hours => 12),
                    created_at  = ((current_date at time zone 'UTC') - make_interval(days => ?))
                                  + make_interval(hours => 12) - make_interval(mins => ?),
                    dispatch_by = ((current_date at time zone 'UTC') - make_interval(days => ?))
                                  + make_interval(hours => 12) + make_interval(mins => ?)
                where order_id = ?
                """, shipDaysAgo, shipDaysAgo, cycleMinutes, shipDaysAgo, cutoffOffsetMinutes, o.id());
    }

    @Test
    void slaComputesOnTimePctAndCycleMedianForTodayAndPerDay() {
        UUID wh = UUID.randomUUID();
        when(allocation.allocate(any(), any(), anyList(), any(), anyBoolean())).thenAnswer(invocation ->
                new AllocationClient.AllocationResult("FULFILLABLE", null,
                        List.of(new AllocationClient.LineResult(1, new BigDecimal("5"), "ALLOCATED"))));

        // Today: 3 shipped. cutoffOffset > 0 => cut-off after ship => on-time; < 0 => late.
        shipWithTimes(wh, "T-1", 10, 5, 0);   // cycle 10, on-time
        shipWithTimes(wh, "T-2", 20, 30, 0);  // cycle 20, on-time
        shipWithTimes(wh, "T-3", 30, -15, 0); // cycle 30, late (shipped after cut-off)
        // 3 days ago: 1 shipped, on-time, cycle 40.
        shipWithTimes(wh, "D-1", 40, 60, 3);

        SlaReport sla = dashboards.sla(wh, 30);

        // Today: 2 of 3 on-time = 66.67%, median cycle of {10,20,30} = 20.
        assertThat(sla.onTimeToCutoffPctToday()).isCloseTo(66.6667, org.assertj.core.data.Offset.offset(0.01));
        assertThat(sla.orderCycleTimeMedianMinToday()).isCloseTo(20.0, org.assertj.core.data.Offset.offset(0.5));

        assertThat(sla.perDay()).hasSize(30);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        SlaReport.DayBucket todayBucket = sla.perDay().get(sla.perDay().size() - 1);
        assertThat(todayBucket.day()).isEqualTo(today);
        assertThat(todayBucket.onTimePct()).isCloseTo(66.6667, org.assertj.core.data.Offset.offset(0.01));
        assertThat(todayBucket.cycleMedianMin()).isCloseTo(20.0, org.assertj.core.data.Offset.offset(0.5));

        SlaReport.DayBucket threeDaysAgo = sla.perDay().get(sla.perDay().size() - 4);
        assertThat(threeDaysAgo.day()).isEqualTo(today.minusDays(3));
        assertThat(threeDaysAgo.onTimePct()).isEqualTo(100.0); // single on-time order
        assertThat(threeDaysAgo.cycleMedianMin()).isCloseTo(40.0, org.assertj.core.data.Offset.offset(0.5));

        // A day with no shipments has null figures.
        SlaReport.DayBucket emptyDay = sla.perDay().get(sla.perDay().size() - 2);
        assertThat(emptyDay.day()).isEqualTo(today.minusDays(1));
        assertThat(emptyDay.onTimePct()).isNull();
        assertThat(emptyDay.cycleMedianMin()).isNull();
    }

    @Test
    void slaIsNullWhenNothingShipped() {
        SlaReport sla = dashboards.sla(UUID.randomUUID(), 30);
        assertThat(sla.onTimeToCutoffPctToday()).isNull();
        assertThat(sla.orderCycleTimeMedianMinToday()).isNull();
        assertThat(sla.perDay()).hasSize(30);
        assertThat(sla.perDay().get(sla.perDay().size() - 1).onTimePct()).isNull();
    }

    @Test
    void slaOnTimeIsNullWhenNoShippedOrderCarriesACutoff() {
        UUID wh = UUID.randomUUID();
        when(allocation.allocate(any(), any(), anyList(), any(), anyBoolean())).thenAnswer(invocation ->
                new AllocationClient.AllocationResult("FULFILLABLE", null,
                        List.of(new AllocationClient.LineResult(1, new BigDecimal("5"), "ALLOCATED"))));

        // Ship one order today but leave dispatch_by null -> cycle median present, on-time null.
        OrderView o = create(wh, "NC-1");
        orders.release(o.id());
        orders.ship(o.id(), "tester");

        SlaReport sla = dashboards.sla(wh, 30);
        assertThat(sla.onTimeToCutoffPctToday()).isNull();         // no cut-off carried
        assertThat(sla.orderCycleTimeMedianMinToday()).isNotNull(); // cycle still computable
    }
}
