package org.openwcs.masterdata;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
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
 * With security enabled, the RBAC filter enforces MASTER_DATA_VIEW on reads and
 * MASTER_DATA_EDIT on writes against the forwarded X-Auth-Roles.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MasterDataRbacTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("openwcs.security.enabled", () -> "true");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper om;

    @Test
    void readRequiresViewPermission() throws Exception {
        mockMvc.perform(get("/api/master-data/warehouses")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/master-data/warehouses").header("X-Auth-Roles", "VIEWER"))
                .andExpect(status().isOk());
    }

    @Test
    void writeRequiresEditPermission() throws Exception {
        String body = om.writeValueAsString(Map.of("code", "WH-RBAC", "name", "RBAC DC"));
        mockMvc.perform(post("/api/master-data/warehouses")
                        .header("X-Auth-Roles", "VIEWER")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/master-data/warehouses")
                        .header("X-Auth-Roles", "SUPERVISOR")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }
}
