package org.openwcs.masterdata;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Stock rules are global admin toggles (mirroring demo/emulator): reads are open, writes are
 * ADMIN-only, and the single-SKU-per-compartment rule defaults to ON.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class StockRulesTest {

    private static final String INTERACTIVE = "X-Auth-Roles";

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
    void defaultsOnAndReadIsOpen() throws Exception {
        mockMvc.perform(get("/api/master-data/stock-rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.singleSkuPerCompartment").value(true));
    }

    @Test
    void nonAdminCannotToggle() throws Exception {
        mockMvc.perform(post("/api/master-data/stock-rules/single-sku-per-compartment/disable")
                        .header(INTERACTIVE, "SUPERVISOR"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminTogglesOffAndOn() throws Exception {
        mockMvc.perform(post("/api/master-data/stock-rules/single-sku-per-compartment/disable")
                        .header(INTERACTIVE, "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.singleSkuPerCompartment").value(false));
        mockMvc.perform(get("/api/master-data/stock-rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.singleSkuPerCompartment").value(false));
        mockMvc.perform(post("/api/master-data/stock-rules/single-sku-per-compartment/enable")
                        .header(INTERACTIVE, "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.singleSkuPerCompartment").value(true));
    }
}
