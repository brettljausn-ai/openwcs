package org.openwcs.masterdata;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Dashboard alert thresholds are the central store for the dashboards/alerting epic: GET returns the
 * baked defaults merged with stored overrides (reads open), the admin PUT round-trips and is merged
 * over the defaults, and a non-admin PUT is forbidden.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AlertThresholdsTest {

    private static final String ROLES = "X-Auth-Roles";

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

    @Test
    void getReturnsDefaultsAndReadIsOpen() throws Exception {
        mockMvc.perform(get("/api/master-data/alert-thresholds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scanNoReadPct").value(1.0))
                .andExpect(jsonPath("$.receiveErrorsDay").value(5))
                .andExpect(jsonPath("$.asrsUtilisationPct").value(85))
                .andExpect(jsonPath("$.blockedLinesPct").value(5))
                .andExpect(jsonPath("$.putawayBacklogAgeMin").value(120))
                .andExpect(jsonPath("$.replenishmentOldestAgeMin").value(120));
    }

    @Test
    void nonAdminCannotWrite() throws Exception {
        mockMvc.perform(put("/api/master-data/alert-thresholds")
                        .header(ROLES, "SUPERVISOR")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scanNoReadPct\":2.5}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminPutRoundTripsMergedOverDefaults() throws Exception {
        mockMvc.perform(put("/api/master-data/alert-thresholds")
                        .header(ROLES, "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scanNoReadPct\":2.5,\"receiveErrorsDay\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scanNoReadPct").value(2.5))     // overridden
                .andExpect(jsonPath("$.receiveErrorsDay").value(10))   // overridden
                .andExpect(jsonPath("$.asrsUtilisationPct").value(85)); // still default

        mockMvc.perform(get("/api/master-data/alert-thresholds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scanNoReadPct").value(2.5))
                .andExpect(jsonPath("$.receiveErrorsDay").value(10))
                .andExpect(jsonPath("$.asrsUtilisationPct").value(85));

        // Restore defaults so the shared-container GET-defaults test is order-independent.
        mockMvc.perform(put("/api/master-data/alert-thresholds")
                        .header(ROLES, "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scanNoReadPct").value(1.0));
    }
}
