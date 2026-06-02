package org.openwcs.orders;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.orders.api.CreateOrderRequest;
import org.openwcs.orders.api.OrderView;
import org.openwcs.orders.api.PostTransactionRequest;
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
 * Boots order-management against PostgreSQL 16 (relay disabled). Verifies that posting a
 * receipt against an INBOUND line records the transaction (with the actor, event id pending)
 * and stages the outbox row in the same transaction — the basis of the audit guarantee.
 */
@SpringBootTest
@Testcontainers
class OrderTransactionTest {

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

    @Test
    void inboundReceiptRecordsTransactionAndStagesOutbox() {
        UUID warehouse = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        UUID location = UUID.randomUUID();

        OrderView created = service.create(new CreateOrderRequest(
                "ASN-1", warehouse, "INBOUND", null, null, null, null, null,
                List.of(new CreateOrderRequest.Line(sku, new BigDecimal("10")))));

        OrderView view = service.postTransaction(created.id(), 1,
                new PostTransactionRequest(new BigDecimal("4"), location, null, null, "EACH", null, null),
                "operator-1"); // resolved actor (gateway-forwarded user)

        OrderView.LineView line = view.lines().get(0);
        assertThat(line.postedQty()).isEqualByComparingTo("4");
        assertThat(line.transactions()).hasSize(1);
        assertThat(line.transactions().get(0).txnType()).isEqualTo("RECEIPT");
        assertThat(line.transactions().get(0).eventId()).isNull(); // filled in by the relay

        List<OrderOutboxMessage> staged = outbox.findAll();
        assertThat(staged).hasSize(1);
        assertThat(staged.get(0).getEventType()).isEqualTo("GoodsReceived");
        assertThat(staged.get(0).getActor()).isEqualTo("operator-1");
        assertThat(staged.get(0).getPublishedAt()).isNull();
    }

    @Test
    void releaseSurfacesCubingFailureOnTheOrder() {
        UUID warehouse = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        String reason = "SKU " + sku + " (line 1) does not fit the largest shipper CARTON-L";
        org.mockito.Mockito.when(allocation.allocate(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AllocationClient.AllocationResult("CUBING_FAILED", reason));

        OrderView created = service.create(new CreateOrderRequest(
                "ORD-BIG", warehouse, "OUTBOUND", null, null, null, null, null,
                List.of(new CreateOrderRequest.Line(sku, new BigDecimal("1")))));

        OrderView released = service.release(created.id());

        assertThat(released.status()).isEqualTo("CUBING_FAILED");
        assertThat(released.statusDetail()).isEqualTo(reason);
    }

    @Test
    void createValidatesServiceAndRouteAgainstMasterData() {
        UUID warehouse = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        org.mockito.Mockito.when(masterData.shippingServiceExists("EXPRESS")).thenReturn(true);
        org.mockito.Mockito.when(masterData.routeExists("CENTRAL_LONDON")).thenReturn(true);

        OrderView created = service.create(new CreateOrderRequest(
                "ORD-SVC", warehouse, "OUTBOUND", null, null, null, "EXPRESS", "CENTRAL_LONDON",
                List.of(new CreateOrderRequest.Line(sku, BigDecimal.ONE))));

        assertThat(created.serviceCode()).isEqualTo("EXPRESS");
        assertThat(created.routeCode()).isEqualTo("CENTRAL_LONDON");
    }

    @Test
    void createRejectsUnknownRoute() {
        UUID warehouse = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        org.mockito.Mockito.when(masterData.routeExists("NOPE")).thenReturn(false);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.create(new CreateOrderRequest(
                        "ORD-BAD", warehouse, "OUTBOUND", null, null, null, null, "NOPE",
                        List.of(new CreateOrderRequest.Line(sku, BigDecimal.ONE)))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
