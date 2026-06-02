package org.openwcs.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.orders.api.CreateOrderRequest;
import org.openwcs.orders.api.OrderView;
import org.openwcs.orders.api.PostTransactionRequest;
import org.openwcs.orders.client.AllocationClient;
import org.openwcs.orders.client.TxLogClient;
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
 * Boots order-management against PostgreSQL 16 with the allocation + txlog clients mocked.
 * Verifies that posting a receipt against an INBOUND line appends a GoodsReceived event to
 * the transaction log and records the transaction (with the event id) + posted-qty rollup.
 */
@SpringBootTest
@Testcontainers
class OrderTransactionTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockBean
    AllocationClient allocation;

    @MockBean
    TxLogClient txlog;

    @Autowired
    OrderService service;

    @Test
    void inboundReceiptPostsEventAndRecordsTransaction() {
        UUID warehouse = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        UUID location = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        when(txlog.append(any(), eq("GoodsReceived"), any(), any(), any())).thenReturn(eventId);

        OrderView created = service.create(new CreateOrderRequest(
                "ASN-1", warehouse, "INBOUND", null, null, null,
                List.of(new CreateOrderRequest.Line(sku, new BigDecimal("10")))));

        OrderView view = service.postTransaction(created.id(), 1,
                new PostTransactionRequest(new BigDecimal("4"), location, null, null, "EACH", null, "operator-1"));

        OrderView.LineView line = view.lines().get(0);
        assertThat(line.postedQty()).isEqualByComparingTo("4");
        assertThat(line.transactions()).hasSize(1);
        assertThat(line.transactions().get(0).txnType()).isEqualTo("RECEIPT");
        assertThat(line.transactions().get(0).eventId()).isEqualTo(eventId);
        verify(txlog).append(eq(line.id().toString()), eq("GoodsReceived"), eq(created.id()), eq("operator-1"), any());
    }
}
