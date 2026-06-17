package org.openwcs.orders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.orders.api.CreateOrderRequest;
import org.openwcs.orders.client.AllocationClient;
import org.openwcs.orders.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Scan verification: GET /api/orders/resolve?warehouseId&ref resolves an order / ASN by its
 * reference (barcode). Verifies an outbound picksheet ref resolves to found:true with orderType
 * OUTBOUND and the correct line count, an inbound ASN ref resolves to orderType INBOUND, an
 * unknown ref resolves to found:false (HTTP 200, not 404), and resolution is warehouse-scoped.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class OrderResolveTest {

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
    MockMvc mockMvc;

    /** Create an order of the given type with {@code lineCount} lines. */
    private void createOrder(String ref, UUID warehouse, String type, String customerRef, int lineCount) {
        java.util.List<CreateOrderRequest.Line> lines = new java.util.ArrayList<>();
        for (int i = 0; i < lineCount; i++) {
            lines.add(new CreateOrderRequest.Line(UUID.randomUUID(), new BigDecimal("1")));
        }
        service.create(new CreateOrderRequest(
                ref, warehouse, type, customerRef, null, null, null, null, null, null, lines));
    }

    @Test
    void resolvesOutboundOrderByRefWithLineCount() throws Exception {
        UUID warehouse = UUID.randomUUID();
        createOrder("ORD-RES-OUT", warehouse, "OUTBOUND", "CUST-1", 2);

        mockMvc.perform(get("/api/orders/resolve")
                        .header("X-Auth-Roles", "VIEWER")
                        .param("warehouseId", warehouse.toString())
                        .param("ref", "ORD-RES-OUT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.order.orderRef").value("ORD-RES-OUT"))
                .andExpect(jsonPath("$.order.orderType").value("OUTBOUND"))
                .andExpect(jsonPath("$.order.status").value("CREATED"))
                .andExpect(jsonPath("$.order.customerRef").value("CUST-1"))
                .andExpect(jsonPath("$.order.lineCount").value(2))
                .andExpect(jsonPath("$.order.orderId").isNotEmpty());
    }

    @Test
    void resolvesInboundAsnByRef() throws Exception {
        UUID warehouse = UUID.randomUUID();
        createOrder("ASN-RES-IN", warehouse, "INBOUND", null, 1);

        mockMvc.perform(get("/api/orders/resolve")
                        .header("X-Auth-Roles", "VIEWER")
                        .param("warehouseId", warehouse.toString())
                        .param("ref", "ASN-RES-IN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.order.orderRef").value("ASN-RES-IN"))
                .andExpect(jsonPath("$.order.orderType").value("INBOUND"))
                .andExpect(jsonPath("$.order.lineCount").value(1));
    }

    @Test
    void unknownRefResolvesToNotFoundWith200() throws Exception {
        UUID warehouse = UUID.randomUUID();

        mockMvc.perform(get("/api/orders/resolve")
                        .header("X-Auth-Roles", "VIEWER")
                        .param("warehouseId", warehouse.toString())
                        .param("ref", "DOES-NOT-EXIST-" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(false))
                .andExpect(jsonPath("$.order").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void resolutionIsWarehouseScoped() throws Exception {
        UUID warehouse = UUID.randomUUID();
        UUID otherWarehouse = UUID.randomUUID();
        createOrder("ORD-RES-SCOPE", warehouse, "OUTBOUND", null, 1);

        // The ref exists, but in another warehouse → not returned for otherWarehouse.
        mockMvc.perform(get("/api/orders/resolve")
                        .header("X-Auth-Roles", "VIEWER")
                        .param("warehouseId", otherWarehouse.toString())
                        .param("ref", "ORD-RES-SCOPE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(false))
                .andExpect(jsonPath("$.order").value(org.hamcrest.Matchers.nullValue()));

        // Resolved correctly under its own warehouse.
        mockMvc.perform(get("/api/orders/resolve")
                        .header("X-Auth-Roles", "VIEWER")
                        .param("warehouseId", warehouse.toString())
                        .param("ref", "ORD-RES-SCOPE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true));
    }
}
