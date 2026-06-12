package org.openwcs.masterdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
 * Lazily-created operational locations ({@code GET /api/master-data/locations/operational}):
 * each conveyor / workplace automatically has a location carrying its name and every warehouse
 * has an UNKNOWN catch-all; the endpoint creates on first use and is idempotent after.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class OperationalLocationTest {

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
    ObjectMapper om;

    private String createWarehouse(String code) throws Exception {
        String body = mockMvc.perform(post("/api/master-data/warehouses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("code", code, "name", code))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return om.readTree(body).get("id").asText();
    }

    @Test
    void equipmentLocationIsCreatedOnceAndReusedAfter() throws Exception {
        String warehouseId = createWarehouse("WH-OP-EQ");

        String first = mockMvc.perform(get("/api/master-data/locations/operational")
                        .param("warehouseId", warehouseId)
                        .param("kind", "EQUIPMENT")
                        .param("name", "BIN_CONVEYOR-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("BIN_CONVEYOR-1"))
                .andExpect(jsonPath("$.locationType").value("CONVEYOR_SEGMENT"))
                .andExpect(jsonPath("$.purpose").value("TRANSPORT"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();

        String second = mockMvc.perform(get("/api/master-data/locations/operational")
                        .param("warehouseId", warehouseId)
                        .param("kind", "EQUIPMENT")
                        .param("name", "BIN_CONVEYOR-1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Idempotent: the repeat call resolves to the SAME location, not a duplicate.
        assertThat(om.readTree(second).get("id").asText())
                .isEqualTo(om.readTree(first).get("id").asText());
    }

    @Test
    void workplaceLocationCarriesTheWorkplaceName() throws Exception {
        String warehouseId = createWarehouse("WH-OP-WP");

        mockMvc.perform(get("/api/master-data/locations/operational")
                        .param("warehouseId", warehouseId)
                        .param("kind", "WORKPLACE")
                        .param("name", "PP1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("PP1"))
                .andExpect(jsonPath("$.locationType").value("STATION"))
                .andExpect(jsonPath("$.purpose").value("STAGING"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void unknownLocationNeedsNoNameAndIsIdempotent() throws Exception {
        String warehouseId = createWarehouse("WH-OP-UNK");

        String first = mockMvc.perform(get("/api/master-data/locations/operational")
                        .param("warehouseId", warehouseId)
                        .param("kind", "UNKNOWN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("UNKNOWN"))
                .andExpect(jsonPath("$.locationType").value("FREE_SPACE"))
                .andExpect(jsonPath("$.purpose").value("QUARANTINE"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();

        String second = mockMvc.perform(get("/api/master-data/locations/operational")
                        .param("warehouseId", warehouseId)
                        .param("kind", "UNKNOWN"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(om.readTree(second).get("id").asText())
                .isEqualTo(om.readTree(first).get("id").asText());
    }

    @Test
    void unsupportedKindIsBadRequest() throws Exception {
        String warehouseId = createWarehouse("WH-OP-BAD");

        mockMvc.perform(get("/api/master-data/locations/operational")
                        .param("warehouseId", warehouseId)
                        .param("kind", "SPACESHIP")
                        .param("name", "X"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void equipmentWithoutANameIsBadRequest() throws Exception {
        String warehouseId = createWarehouse("WH-OP-NONAME");

        mockMvc.perform(get("/api/master-data/locations/operational")
                        .param("warehouseId", warehouseId)
                        .param("kind", "EQUIPMENT"))
                .andExpect(status().isBadRequest());
    }
}
