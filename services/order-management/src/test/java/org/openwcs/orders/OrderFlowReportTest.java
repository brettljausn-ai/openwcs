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
import org.openwcs.orders.api.OrderFlowReport;
import org.openwcs.orders.api.OrderView;
import org.openwcs.orders.api.PostTransactionRequest;
import org.openwcs.orders.client.AllocationClient;
import org.openwcs.orders.domain.OrderType;
import org.openwcs.orders.service.OrderFlowReportService;
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
 * Order-flow report against a real PostgreSQL 16: seeded orders across statuses and
 * timestamps produce the documented expected / active / started figures, the per-day
 * window, and the hour-of-day intake histogram.
 */
@SpringBootTest
@Testcontainers
class OrderFlowReportTest {

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
    OrderFlowReportService reports;

    @Autowired
    JdbcTemplate jdbc;

    private OrderView create(UUID wh, String ref, String type, String qty) {
        return orders.create(new CreateOrderRequest(
                ref, wh, type, null, null, null, null, null, null, null,
                List.of(new CreateOrderRequest.Line(UUID.randomUUID(), new BigDecimal(qty)))));
    }

    private void post(UUID orderId, String qty) {
        orders.postTransaction(orderId, 1,
                new PostTransactionRequest(new BigDecimal(qty), UUID.randomUUID(), null, null,
                        "EACH", null, null),
                "tester");
    }

    private void backdateCreated(UUID orderId, int daysAgo) {
        jdbc.update("update orders.outbound_order set created_at = now() - make_interval(days => ?)"
                + " where order_id = ?", daysAgo, orderId);
    }

    @Test
    void inboundFlowSplitsExpectedActiveStartedAndBuckets() {
        UUID wh = UUID.randomUUID();
        OrderView waiting = create(wh, "IN-1", "INBOUND", "10");   // received, no stock yet
        OrderView receiving = create(wh, "IN-2", "INBOUND", "10"); // partially received
        post(receiving.id(), "4");
        OrderView done = create(wh, "IN-3", "INBOUND", "5");       // fully received
        post(done.id(), "5");
        OrderView cancelled = create(wh, "IN-4", "INBOUND", "5");  // must not count as expected/active
        orders.cancel(cancelled.id());
        OrderView old = create(wh, "IN-5", "INBOUND", "10");       // received 5 days ago, still open
        backdateCreated(old.id(), 5);

        OrderFlowReport flow = reports.flow(wh, OrderType.INBOUND, 90);

        assertThat(flow.expected()).isEqualTo(2); // IN-1 + IN-5: received but no stock posted
        assertThat(flow.active()).isEqualTo(3);   // IN-1, IN-2, IN-5 still await stock
        assertThat(flow.started()).isEqualTo(1);  // only IN-2 has receipts and is still open

        assertThat(flow.perDay()).hasSize(90);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        OrderFlowReport.DayBucket todayBucket = flow.perDay().get(flow.perDay().size() - 1);
        assertThat(todayBucket.day()).isEqualTo(today);
        assertThat(todayBucket.received()).isEqualTo(4);  // IN-1..IN-4 (cancelled was still received)
        assertThat(todayBucket.started()).isEqualTo(2);   // first receipts of IN-2 and IN-3
        assertThat(todayBucket.completed()).isEqualTo(1); // IN-3 fully received today
        OrderFlowReport.DayBucket oldBucket = flow.perDay().get(flow.perDay().size() - 6);
        assertThat(oldBucket.day()).isEqualTo(today.minusDays(5));
        assertThat(oldBucket.received()).isEqualTo(1);    // IN-5

        assertThat(flow.hourOfDay()).hasSize(24);
        long intake = flow.hourOfDay().stream().mapToLong(OrderFlowReport.HourBucket::count).sum();
        assertThat(intake).isEqualTo(5); // all five received within the 90-day window
    }

    @Test
    void windowCutsOffOrdersReceivedBeforeIt() {
        UUID wh = UUID.randomUUID();
        OrderView recent = create(wh, "WIN-1", "INBOUND", "10");
        OrderView old = create(wh, "WIN-2", "INBOUND", "10");
        backdateCreated(old.id(), 10);

        OrderFlowReport flow = reports.flow(wh, OrderType.INBOUND, 3);

        assertThat(flow.expected()).isEqualTo(2); // headline figures are current state, unwindowed
        assertThat(flow.perDay()).hasSize(3);
        long received = flow.perDay().stream().mapToLong(OrderFlowReport.DayBucket::received).sum();
        assertThat(received).isEqualTo(1); // only WIN-1 was received inside the window
        long intake = flow.hourOfDay().stream().mapToLong(OrderFlowReport.HourBucket::count).sum();
        assertThat(intake).isEqualTo(1);
        assertThat(recent.id()).isNotNull();
    }

    @Test
    void outboundFlowSplitsExpectedActiveStartedAndCompleted() {
        UUID wh = UUID.randomUUID();
        when(allocation.allocate(any(), any(), anyList(), any(), anyBoolean())).thenAnswer(invocation -> {
            String orderRef = invocation.getArgument(0);
            return "OUT-4".equals(orderRef)
                    ? new AllocationClient.AllocationResult("NOT_FULFILLABLE", "no stock", java.util.List.of())
                    : new AllocationClient.AllocationResult("FULFILLABLE", null, java.util.List.of());
        });

        OrderView created = create(wh, "OUT-1", "OUTBOUND", "5");   // received, not released
        OrderView picking = create(wh, "OUT-2", "OUTBOUND", "5");   // released + first pick posted
        orders.release(picking.id());
        post(picking.id(), "2");
        OrderView shipped = create(wh, "OUT-3", "OUTBOUND", "5");   // released and shipped
        orders.release(shipped.id());
        orders.ship(shipped.id(), "tester");
        OrderView short_ = create(wh, "OUT-4", "OUTBOUND", "5");    // released, not fulfillable
        orders.release(short_.id());

        OrderFlowReport flow = reports.flow(wh, OrderType.OUTBOUND, 90);

        assertThat(flow.expected()).isEqualTo(1); // OUT-1: received but not yet released
        assertThat(flow.active()).isEqualTo(2);   // OUT-2 picking, OUT-4 awaiting stock
        assertThat(flow.started()).isEqualTo(1);  // only OUT-2 has picks posted

        OrderFlowReport.DayBucket today = flow.perDay().get(flow.perDay().size() - 1);
        assertThat(today.received()).isEqualTo(4);
        assertThat(today.started()).isEqualTo(1);  // OUT-2's first pick
        assertThat(today.completed()).isEqualTo(1); // OUT-3 shipped today
        assertThat(created.id()).isNotNull();
    }
}
