package org.openwcs.masterdata;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

/** Covers the shipping-service + route catalogs: create, look up by code, and archive. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class DispatchCatalogApiTest {

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
    void shippingServiceLifecycle() throws Exception {
        String created = mockMvc.perform(post("/api/master-data/shipping-services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "code", "EXPRESS", "name", "Next-day express", "carrier", "DPD",
                                "labelTemplateCode", "SHIP-4X6"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.labelTemplateCode").value("SHIP-4X6"))
                .andReturn().getResponse().getContentAsString();
        String id = om.readTree(created).get("id").asText();

        mockMvc.perform(get("/api/master-data/shipping-services").param("code", "EXPRESS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].carrier").value("DPD"));

        mockMvc.perform(delete("/api/master-data/shipping-services/" + id))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/master-data/shipping-services/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
    }

    @Test
    void routeFromHostIsLookableByCode() throws Exception {
        mockMvc.perform(post("/api/master-data/routes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "code", "CENTRAL_LONDON", "name", "Central London",
                                "region", "London", "hostRef", "SAP-ROUTE-42"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.hostRef").value("SAP-ROUTE-42"));

        mockMvc.perform(get("/api/master-data/routes").param("code", "CENTRAL_LONDON"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Central London"));

        mockMvc.perform(get("/api/master-data/routes").param("code", "NOPE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
