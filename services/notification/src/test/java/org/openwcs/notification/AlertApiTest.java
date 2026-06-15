package org.openwcs.notification;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.notification.client.MetricsClient;
import org.openwcs.notification.service.AlertService;
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

/** The alerts API returns active alerts for a warehouse, and ack transitions an alert to ACKED. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AlertApiTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AlertService alerts;

    // The evaluator's metric client is not exercised here, but is a required bean.
    @MockBean
    MetricsClient metrics;

    @Test
    void listsActiveAlertsAndAcksOne() throws Exception {
        UUID warehouse = UUID.randomUUID();
        UUID id = alerts.raise(warehouse, "SCAN", "scanNoReadPct", "WARNING",
                new BigDecimal("6.0"), new BigDecimal("5.0")).getId();

        mockMvc.perform(get("/api/notification/alerts").param("warehouseId", warehouse.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].area").value("SCAN"))
                .andExpect(jsonPath("$[0].metric").value("scanNoReadPct"))
                .andExpect(jsonPath("$[0].severity").value("WARNING"))
                .andExpect(jsonPath("$[0].state").value("OPEN"))
                .andExpect(jsonPath("$[0].sinceIso").isNotEmpty());

        mockMvc.perform(post("/api/notification/alerts/{id}/ack", id)
                        .header("X-Auth-User", "supervisor1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ACKED"));

        // Still active (ACKED) and now attributed.
        mockMvc.perform(get("/api/notification/alerts").param("warehouseId", warehouse.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].state").value("ACKED"));
    }

    @Test
    void ackUnknownAlertIs404() throws Exception {
        mockMvc.perform(post("/api/notification/alerts/{id}/ack", UUID.randomUUID())
                        .header("X-Auth-User", "supervisor1"))
                .andExpect(status().isNotFound());
    }
}
