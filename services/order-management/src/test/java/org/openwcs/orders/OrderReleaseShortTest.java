package org.openwcs.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.orders.api.CreateOrderRequest;
import org.openwcs.orders.api.IllegalOrderStateException;
import org.openwcs.orders.api.OrderView;
import org.openwcs.orders.client.AllocationClient;
import org.openwcs.orders.domain.OrderOutboxMessage;
import org.openwcs.orders.repo.OrderOutboxRepository;
import org.openwcs.orders.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Short allocate and release: the supervisor decision to work a short order with whatever is
 * available. Verifies the state transition to PARTIALLY_ALLOCATED with per-line short
 * quantities, the rejections (not short / nothing available), and that dispatching a
 * short-released order stages an OrderShipped confirmation with shipped &lt; ordered.
 */
@SpringBootTest
@Testcontainers
class OrderReleaseShortTest {

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
    OrderService service;

    @Autowired
    OrderOutboxRepository outbox;

    private OrderView shortOrder(String ref) {
        UUID warehouse = UUID.randomUUID();
        OrderView created = service.create(new CreateOrderRequest(
                ref, warehouse, "OUTBOUND", null, null, null, null, null, null, null,
                List.of(new CreateOrderRequest.Line(UUID.randomUUID(), new BigDecimal("5")),
                        new CreateOrderRequest.Line(UUID.randomUUID(), new BigDecimal("2")))));
        // Strict release finds the order short: NOT_FULFILLABLE, nothing held.
        when(allocation.allocate(eq(ref), any(), anyList(), any(), eq(false)))
                .thenReturn(new AllocationClient.AllocationResult("NOT_FULFILLABLE", "2 lines short", List.of()));
        OrderView released = service.release(created.id());
        assertThat(released.status()).isEqualTo("NOT_FULFILLABLE");
        return released;
    }

    @Test
    void releaseShortTransitionsToPartiallyAllocatedWithPerLineShortQuantities() {
        OrderView order = shortOrder("ORD-RS-1");

        // The allow-short re-run reserves 3 of 5 on line 1; line 2 has nothing (fully short).
        when(allocation.allocate(eq("ORD-RS-1"), any(), anyList(), any(), eq(true)))
                .thenReturn(new AllocationClient.AllocationResult("FULFILLABLE_SHORT",
                        "Short allocated: line 1 short 2 of 5; line 2 short 2 of 2",
                        List.of(new AllocationClient.LineResult(1, new BigDecimal("3"), "SHORT"),
                                new AllocationClient.LineResult(2, BigDecimal.ZERO, "SHORT"))));

        OrderView shortReleased = service.releaseShort(order.id(), "supervisor-1");

        assertThat(shortReleased.status()).isEqualTo("PARTIALLY_ALLOCATED");
        assertThat(shortReleased.statusDetail()).contains("short released by supervisor-1");
        assertThat(shortReleased.lines().get(0).allocatedQty()).isEqualByComparingTo("3");
        assertThat(shortReleased.lines().get(0).status()).isEqualTo("SHORT");
        assertThat(shortReleased.lines().get(1).allocatedQty()).isEqualByComparingTo("0");
        assertThat(shortReleased.lines().get(1).status()).isEqualTo("SHORT");
    }

    @Test
    void releaseShortRejectsOrdersThatAreNotShort() {
        UUID warehouse = UUID.randomUUID();
        OrderView created = service.create(new CreateOrderRequest(
                "ORD-RS-2", warehouse, "OUTBOUND", null, null, null, null, null, null, null,
                List.of(new CreateOrderRequest.Line(UUID.randomUUID(), BigDecimal.ONE))));

        // CREATED (never found short) — the decision does not apply.
        assertThatThrownBy(() -> service.releaseShort(created.id(), "supervisor-1"))
                .isInstanceOf(IllegalOrderStateException.class)
                .hasMessageContaining("NOT_FULFILLABLE");

        // Already (fully) released — same rejection.
        when(allocation.allocate(eq("ORD-RS-2"), any(), anyList(), any(), eq(false)))
                .thenReturn(new AllocationClient.AllocationResult("FULFILLABLE", null,
                        List.of(new AllocationClient.LineResult(1, BigDecimal.ONE, "ALLOCATED"))));
        service.release(created.id());
        assertThatThrownBy(() -> service.releaseShort(created.id(), "supervisor-1"))
                .isInstanceOf(IllegalOrderStateException.class)
                .hasMessageContaining("was ALLOCATED");
    }

    @Test
    void releaseShortRefusesWhenNothingIsAvailableAtAll() {
        OrderView order = shortOrder("ORD-RS-3");
        // Even allow-short finds zero stock on every line: nothing would be picked.
        when(allocation.allocate(eq("ORD-RS-3"), any(), anyList(), any(), eq(true)))
                .thenReturn(new AllocationClient.AllocationResult("NOT_FULFILLABLE", "2 lines short", List.of()));

        assertThatThrownBy(() -> service.releaseShort(order.id(), "supervisor-1"))
                .isInstanceOf(IllegalOrderStateException.class)
                .hasMessageContaining("no stock is available");
    }

    @Test
    void shippingAShortReleasedOrderStagesOrderShippedWithShippedLessThanOrdered() {
        OrderView order = shortOrder("ORD-RS-4");
        when(allocation.allocate(eq("ORD-RS-4"), any(), anyList(), any(), eq(true)))
                .thenReturn(new AllocationClient.AllocationResult("FULFILLABLE_SHORT",
                        "Short allocated: line 1 short 2 of 5; line 2 short 2 of 2",
                        List.of(new AllocationClient.LineResult(1, new BigDecimal("3"), "SHORT"),
                                new AllocationClient.LineResult(2, BigDecimal.ZERO, "SHORT"))));
        service.releaseShort(order.id(), "supervisor-1");

        OrderView shipped = service.ship(order.id(), "dispatcher-1");

        assertThat(shipped.status()).isEqualTo("SHIPPED");
        List<OrderOutboxMessage> staged = outbox.findAll().stream()
                .filter(m -> "OrderShipped".equals(m.getEventType()))
                .toList();
        assertThat(staged).hasSize(1);
        OrderOutboxMessage event = staged.get(0);
        assertThat(event.getStreamId()).isEqualTo("ORD-RS-4");
        assertThat(event.getActor()).isEqualTo("dispatcher-1");
        assertThat(event.getLineTxnId()).isNull(); // order-level event: no source line transaction
        Map<String, Object> payload = event.getPayload();
        assertThat(payload.get("shortShipped")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) payload.get("lines");
        assertThat(lines).hasSize(2);
        // The host sees the short ship: shipped < ordered per short line.
        assertThat(new BigDecimal(lines.get(0).get("orderedQty").toString())).isEqualByComparingTo("5");
        assertThat(new BigDecimal(lines.get(0).get("shippedQty").toString())).isEqualByComparingTo("3");
        assertThat(new BigDecimal(lines.get(0).get("shortQty").toString())).isEqualByComparingTo("2");
        assertThat(new BigDecimal(lines.get(1).get("shippedQty").toString())).isEqualByComparingTo("0");
    }
}
