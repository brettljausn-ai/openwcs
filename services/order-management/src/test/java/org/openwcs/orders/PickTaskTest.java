package org.openwcs.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.orders.api.ConfirmPickRequest;
import org.openwcs.orders.api.CreateOrderRequest;
import org.openwcs.orders.api.IllegalOrderStateException;
import org.openwcs.orders.api.OrderView;
import org.openwcs.orders.api.PickTaskView;
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
 * Operator guided-picking flow over RELEASED/ALLOCATED outbound orders (relay disabled).
 * Verifies the pick queue lists released lines needing picking (with sku/qty and the location
 * once known), that confirming a pick posts a Picked transaction (asserted on the outbox) and
 * advances pickedQty/status, that a short confirm marks the line SHORT, and that a fully
 * picked / non-pickable line is rejected (409).
 */
@SpringBootTest
@Testcontainers
class PickTaskTest {

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

    /** Create an OUTBOUND order and fully allocate it (status ALLOCATED, lines ALLOCATED). */
    private OrderView allocatedOrder(String ref, UUID warehouse, UUID sku, String qty) {
        return allocatedOrder(ref, warehouse, sku, qty, null);
    }

    /** As above, with the allocation service returning {@code pickLocation} as the line's pick face. */
    private OrderView allocatedOrder(String ref, UUID warehouse, UUID sku, String qty, UUID pickLocation) {
        OrderView created = service.create(new CreateOrderRequest(
                ref, warehouse, "OUTBOUND", null, null, null, null, null, null, null,
                List.of(new CreateOrderRequest.Line(sku, new BigDecimal(qty)))));
        when(allocation.allocate(eq(ref), any(), anyList(), any(), eq(false)))
                .thenReturn(new AllocationClient.AllocationResult("FULFILLABLE", null,
                        List.of(new AllocationClient.LineResult(1, new BigDecimal(qty), "ALLOCATED", pickLocation))));
        OrderView released = service.release(created.id());
        assertThat(released.status()).isEqualTo("ALLOCATED");
        return released;
    }

    @Test
    void pickQueueListsReleasedLinesNeedingPicking() {
        UUID warehouse = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        allocatedOrder("ORD-PQ-1", warehouse, sku, "5");

        List<PickTaskView> tasks = service.pickTasks(warehouse);

        assertThat(tasks).hasSize(1);
        PickTaskView task = tasks.get(0);
        assertThat(task.orderCode()).isEqualTo("ORD-PQ-1");
        assertThat(task.lineNo()).isEqualTo(1);
        assertThat(task.skuId()).isEqualTo(sku);
        assertThat(task.requestedQty()).isEqualByComparingTo("5");
        assertThat(task.pickedQty()).isEqualByComparingTo("0");
        assertThat(task.remainingQty()).isEqualByComparingTo("5");
        assertThat(task.status()).isEqualTo("ALLOCATED");
        // Allocation supplied no pick location (older path) and no pick posted yet → null.
        assertThat(task.locationId()).isNull();
    }

    @Test
    void pickQueueShowsAllocatedPickLocation() {
        UUID warehouse = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        UUID pickFace = UUID.randomUUID();
        // Allocation returns the reserved pick face; it is threaded onto the order line on release.
        allocatedOrder("ORD-PQ-LOC", warehouse, sku, "5", pickFace);

        List<PickTaskView> tasks = service.pickTasks(warehouse);

        assertThat(tasks).hasSize(1);
        // locationId is the allocated pick location, shown before any stock is picked.
        assertThat(tasks.get(0).locationId()).isEqualTo(pickFace);
    }

    @Test
    void confirmPickPostsPickedTransactionAndAdvancesQty() {
        UUID warehouse = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        UUID location = UUID.randomUUID();
        UUID orderId = allocatedOrder("ORD-PQ-2", warehouse, sku, "5").id();
        UUID lineId = service.pickTasks(warehouse).get(0).lineId();

        PickTaskView after = service.confirmPick(lineId,
                new ConfirmPickRequest(new BigDecimal("5"), false, location), "operator-1");

        assertThat(after.pickedQty()).isEqualByComparingTo("5");
        assertThat(after.remainingQty()).isEqualByComparingTo("0");
        assertThat(after.locationId()).isEqualTo(location);

        // A Picked transaction was staged on the outbox in the same local transaction (scoped to
        // this order — the shared test container retains rows from sibling tests).
        List<OrderOutboxMessage> picked = outbox.findAll().stream()
                .filter(m -> "Picked".equals(m.getEventType()))
                .filter(m -> orderId.equals(m.getCorrelationId()))
                .toList();
        assertThat(picked).hasSize(1);
        OrderOutboxMessage event = picked.get(0);
        assertThat(event.getActor()).isEqualTo("operator-1");
        assertThat(event.getLineTxnId()).isNotNull(); // links back to the line transaction
        assertThat(event.getPublishedAt()).isNull();   // relay disabled
        assertThat(new BigDecimal(event.getPayload().get("qty").toString())).isEqualByComparingTo("5");
        assertThat(event.getPayload().get("skuId").toString()).isEqualTo(sku.toString());

        // Fully picked → drops out of the queue.
        assertThat(service.pickTasks(warehouse)).isEmpty();
    }

    @Test
    void shortConfirmMarksLineShort() {
        UUID warehouse = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        UUID location = UUID.randomUUID();
        allocatedOrder("ORD-PQ-3", warehouse, sku, "5");
        UUID lineId = service.pickTasks(warehouse).get(0).lineId();

        // Operator could only pick 3 of 5 and flags the line short.
        PickTaskView after = service.confirmPick(lineId,
                new ConfirmPickRequest(new BigDecimal("3"), true, location), "operator-1");

        assertThat(after.pickedQty()).isEqualByComparingTo("3");
        assertThat(after.status()).isEqualTo("SHORT");

        // A SHORT line with picked < requested still leaves a (residual) task in the queue.
        assertThat(service.pickTasks(warehouse)).hasSize(1);
    }

    @Test
    void confirmRejectsAFullyPickedLine() {
        UUID warehouse = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        UUID location = UUID.randomUUID();
        allocatedOrder("ORD-PQ-4", warehouse, sku, "2");
        UUID lineId = service.pickTasks(warehouse).get(0).lineId();
        service.confirmPick(lineId, new ConfirmPickRequest(new BigDecimal("2"), false, location), "operator-1");

        // Already fully picked → no longer pickable.
        assertThatThrownBy(() -> service.confirmPick(lineId,
                new ConfirmPickRequest(new BigDecimal("1"), false, location), "operator-1"))
                .isInstanceOf(IllegalOrderStateException.class)
                .hasMessageContaining("not pickable");
    }

    @Test
    void confirmRejectsAnUnknownLine() {
        assertThatThrownBy(() -> service.confirmPick(UUID.randomUUID(),
                new ConfirmPickRequest(BigDecimal.ONE, false, null), "operator-1"))
                .isInstanceOf(IllegalOrderStateException.class);
    }
}
