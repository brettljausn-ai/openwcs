package org.openwcs.masterdata;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
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

/** End-to-end test of the master-data REST layer (controllers + persistence + error mapping). */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MasterDataApiTest {

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

    @Test
    void skuLifecycleWithProfileOverlay() throws Exception {
        String warehouse = mockMvc.perform(post("/api/master-data/warehouses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("code", "WH-API", "name", "API DC"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String warehouseId = om.readTree(warehouse).get("id").asText();

        String sku = mockMvc.perform(post("/api/master-data/skus")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "code", "SKU-API", "description", "API tee", "batchTracked", true))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andReturn().getResponse().getContentAsString();
        String skuId = om.readTree(sku).get("id").asText();

        mockMvc.perform(get("/api/master-data/skus/{id}", skuId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SKU-API"))
                .andExpect(jsonPath("$.batchTracked").value(true));

        mockMvc.perform(put("/api/master-data/skus/{id}/profiles", skuId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "warehouseId", warehouseId,
                                "metadata", Map.of("brand", "Acme", "season", "SS26")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metadata.brand").value("Acme"));

        mockMvc.perform(get("/api/master-data/skus").param("code", "SKU-API"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].code").value("SKU-API"));
    }

    @Test
    void missingSkuReturns404() throws Exception {
        mockMvc.perform(get("/api/master-data/skus/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void duplicateWarehouseCodeReturns409() throws Exception {
        String body = om.writeValueAsString(Map.of("code", "WH-DUP", "name", "Dup"));
        mockMvc.perform(post("/api/master-data/warehouses").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/master-data/warehouses").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }
}
