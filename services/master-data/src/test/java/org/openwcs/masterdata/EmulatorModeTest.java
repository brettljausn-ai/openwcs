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
 * Hardware emulator mode is a global admin toggle (mirrors demo mode): reads are open, writes are
 * ADMIN-only, and the flag round-trips through {@code system_configuration}. Defaults OFF.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class EmulatorModeTest {

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
    void defaultsOffAndReadIsOpen() throws Exception {
        mockMvc.perform(get("/api/master-data/emulator"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void nonAdminCannotToggle() throws Exception {
        mockMvc.perform(post("/api/master-data/emulator/enable").header(INTERACTIVE, "VIEWER"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminEnablesAndDisables() throws Exception {
        mockMvc.perform(post("/api/master-data/emulator/enable").header(INTERACTIVE, "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

        mockMvc.perform(get("/api/master-data/emulator"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

        mockMvc.perform(post("/api/master-data/emulator/disable").header(INTERACTIVE, "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        mockMvc.perform(get("/api/master-data/emulator"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }
}
