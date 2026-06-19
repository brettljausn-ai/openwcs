package org.openwcs.orders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.orders.client.AllocationClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * RBAC + HTTP behaviour of the pick endpoints with security enabled. Confirming a pick needs
 * the picking write permission (ORDER_POST_TRANSACTION): OPERATOR holds it (200), VIEWER does
 * not (403). A confirm against an unknown / non-pickable line returns 409 over HTTP.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PickTaskAuthorizationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("openwcs.security.enabled", () -> "true");
        registry.add("openwcs.orders.relay.enabled", () -> "false");
    }

    @MockBean
    AllocationClient allocation;

    @MockBean
    org.openwcs.orders.client.MasterDataClient masterData;

    @MockBean
    org.openwcs.orders.client.InventoryClient inventory;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper om;

    /** Create + allocate an OUTBOUND order as SUPERVISOR, returning the pickable line id. */
    private String allocatedLineId(UUID warehouse) throws Exception {
        String ref = "ORD-" + UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        String body = om.writeValueAsString(Map.of(
                "orderRef", ref, "warehouseId", warehouse,
                "lines", List.of(Map.of("skuId", sku, "qty", 5))));
        String created = mockMvc.perform(post("/api/orders")
                        .header("X-Auth-Roles", "SUPERVISOR")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = om.readTree(created).get("id").asText();
        when(allocation.allocate(eq(ref), any(), anyList(), any(), eq(false)))
                .thenReturn(new AllocationClient.AllocationResult("FULFILLABLE", null,
                        List.of(new AllocationClient.LineResult(1, new BigDecimal("5"), "ALLOCATED"))));
        mockMvc.perform(post("/api/orders/{id}/release", id).header("X-Auth-Roles", "SUPERVISOR"))
                .andExpect(status().isOk());
        return om.readTree(created).get("lines").get(0).get("id").asText();
    }

    private String confirmBody() throws Exception {
        // Ample stock at the pick face so the available-at-location guard never clamps these picks.
        when(inventory.availabilityAtLocation(any(), any(), any()))
                .thenReturn(new org.openwcs.orders.client.InventoryClient.Availability(
                        new BigDecimal("999"), BigDecimal.ZERO, new BigDecimal("999")));
        return om.writeValueAsString(Map.of("pickedQty", 5));
    }

    @Test
    void operatorCanConfirmAPick() throws Exception {
        String lineId = allocatedLineId(UUID.randomUUID());
        mockMvc.perform(post("/api/orders/pick-tasks/{lineId}/confirm", lineId)
                        .header("X-Auth-Roles", "OPERATOR")
                        .header("X-Auth-User", "operator-1")
                        .contentType(MediaType.APPLICATION_JSON).content(confirmBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pickedQty").value(5))
                .andExpect(jsonPath("$.remainingQty").value(0));
    }

    @Test
    void viewerCannotConfirmAPick() throws Exception {
        String lineId = allocatedLineId(UUID.randomUUID());
        mockMvc.perform(post("/api/orders/pick-tasks/{lineId}/confirm", lineId)
                        .header("X-Auth-Roles", "VIEWER")
                        .header("X-Auth-User", "viewer-1")
                        .contentType(MediaType.APPLICATION_JSON).content(confirmBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    void operatorCanReadThePickQueue() throws Exception {
        UUID warehouse = UUID.randomUUID();
        allocatedLineId(warehouse);
        mockMvc.perform(get("/api/orders/pick-tasks")
                        .param("warehouseId", warehouse.toString())
                        .header("X-Auth-Roles", "OPERATOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].remainingQty").value(5));
    }

    @Test
    void confirmOnANonPickableLineIs409() throws Exception {
        // An unknown line id passes the permission gate then 409s as not pickable.
        mockMvc.perform(post("/api/orders/pick-tasks/{lineId}/confirm", UUID.randomUUID())
                        .header("X-Auth-Roles", "OPERATOR")
                        .header("X-Auth-User", "operator-1")
                        .contentType(MediaType.APPLICATION_JSON).content(confirmBody()))
                .andExpect(status().isConflict());
    }
}
