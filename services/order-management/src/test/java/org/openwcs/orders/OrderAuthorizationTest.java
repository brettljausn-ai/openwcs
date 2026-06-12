package org.openwcs.orders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * With security enabled, endpoints require the coded permission carried by the
 * gateway-forwarded X-Auth-Roles: VIEWER cannot create an order (403), SUPERVISOR can (201).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class OrderAuthorizationTest {

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

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper om;

    private String orderBody() throws Exception {
        return om.writeValueAsString(Map.of(
                "orderRef", "ORD-" + UUID.randomUUID(),
                "warehouseId", UUID.randomUUID(),
                "lines", List.of(Map.of("skuId", UUID.randomUUID(), "qty", 1))));
    }

    @Test
    void viewerCannotCreateOrder() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .header("X-Auth-Roles", "VIEWER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    void supervisorCanCreateOrder() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .header("X-Auth-Roles", "SUPERVISOR")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody()))
                .andExpect(status().isCreated());
    }

    @Test
    void operatorCannotShortReleaseAnOrder() throws Exception {
        // ORDER_RELEASE is a supervisor-level permission; OPERATOR lacks it.
        mockMvc.perform(post("/api/orders/{id}/release-short", UUID.randomUUID())
                        .header("X-Auth-Roles", "OPERATOR"))
                .andExpect(status().isForbidden());
    }

    @Test
    void supervisorShortReleaseIsAcceptedButRejectsAnOrderThatIsNotShort() throws Exception {
        // Passes the permission gate, then 409s because the order is CREATED (not short).
        String created = mockMvc.perform(post("/api/orders")
                        .header("X-Auth-Roles", "SUPERVISOR")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = om.readTree(created).get("id").asText();

        mockMvc.perform(post("/api/orders/{id}/release-short", id)
                        .header("X-Auth-Roles", "SUPERVISOR")
                        .header("X-Auth-User", "supervisor-1"))
                .andExpect(status().isConflict());
    }
}
