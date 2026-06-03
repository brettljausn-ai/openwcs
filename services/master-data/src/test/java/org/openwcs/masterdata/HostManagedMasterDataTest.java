package org.openwcs.masterdata;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
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
 * SKU / UoM / barcode are host-owned master data (build.md §6, §16): the WCS is a slave to them.
 * Interactive (gateway-routed) callers — identified by the gateway-injected {@code X-Auth-User}
 * header — must NOT be able to create, edit or delete them, while the host-sync ingestion path
 * (a direct internal call with no such header) must still be able to upsert them. Reads stay open.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class HostManagedMasterDataTest {

    /** Header the gateway injects for every authenticated interactive request. */
    private static final String INTERACTIVE = "X-Auth-User";

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

    // ----------------------------------------------------------------- interactive writes blocked
    @Test
    void interactiveCreateSkuIsForbidden() throws Exception {
        mockMvc.perform(post("/api/master-data/skus")
                        .header(INTERACTIVE, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("code", "SKU-INT-1", "description", "nope"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("host system")));
    }

    @Test
    void interactiveUpdateAndDeleteSkuAreForbidden() throws Exception {
        // Seed via the host (header-less) path so there is something to attempt to mutate.
        String sku = mockMvc.perform(post("/api/master-data/skus")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("code", "SKU-INT-2", "description", "seed"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String skuId = om.readTree(sku).get("id").asText();

        mockMvc.perform(put("/api/master-data/skus/{id}", skuId)
                        .header(INTERACTIVE, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("code", "SKU-INT-2", "description", "edited"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/master-data/skus/{id}", skuId).header(INTERACTIVE, "alice"))
                .andExpect(status().isForbidden());
    }

    @Test
    void interactiveCreateUomAndBarcodeAreForbidden() throws Exception {
        String sku = mockMvc.perform(post("/api/master-data/skus")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("code", "SKU-INT-3", "description", "seed"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String skuId = om.readTree(sku).get("id").asText();

        mockMvc.perform(post("/api/master-data/skus/{id}/uoms", skuId)
                        .header(INTERACTIVE, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("code", "EACH", "baseUnit", true))))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/master-data/skus/{id}/barcodes", skuId)
                        .header(INTERACTIVE, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("value", "1234567890123"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void interactiveImportAndBarcodeDeleteAreForbidden() throws Exception {
        mockMvc.perform(post("/api/master-data/skus/import")
                        .header(INTERACTIVE, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(List.of(Map.of("code", "SKU-IMP-1")))))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/master-data/barcodes/{id}", java.util.UUID.randomUUID())
                        .header(INTERACTIVE, "alice"))
                .andExpect(status().isForbidden());
    }

    // ----------------------------------------------------------------------- reads stay open
    @Test
    void interactiveReadsStillWork() throws Exception {
        String sku = mockMvc.perform(post("/api/master-data/skus")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("code", "SKU-READ-1", "description", "readable"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String skuId = om.readTree(sku).get("id").asText();

        // List, search and detail all work for an interactive caller.
        mockMvc.perform(get("/api/master-data/skus").header(INTERACTIVE, "alice"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/master-data/skus").param("q", "SKU-READ").header(INTERACTIVE, "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].code").value("SKU-READ-1"));
        mockMvc.perform(get("/api/master-data/skus/{id}", skuId).header(INTERACTIVE, "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SKU-READ-1"));
        mockMvc.perform(get("/api/master-data/skus/{id}/uoms", skuId).header(INTERACTIVE, "alice"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/master-data/skus/{id}/barcodes", skuId).header(INTERACTIVE, "alice"))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ host-sync upsert succeeds
    @Test
    void hostSyncUpsertPathStillSucceeds() throws Exception {
        // The host integration service calls master-data directly (no gateway, so no X-Auth-User).
        // Create...
        String created = mockMvc.perform(post("/api/master-data/skus")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "code", "SKU-HOST-1", "description", "from host", "batchTracked", true))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String skuId = om.readTree(created).get("id").asText();

        // ...update (the host upsert-by-code does a PUT when the SKU already exists)...
        mockMvc.perform(put("/api/master-data/skus/{id}", skuId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "code", "SKU-HOST-1", "description", "host updated", "batchTracked", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("host updated"));

        // ...and add a UoM + barcode via the host path.
        mockMvc.perform(post("/api/master-data/skus/{id}/uoms", skuId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("code", "EACH", "baseUnit", true))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/master-data/skus/{id}/barcodes", skuId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("value", "4006381333931"))))
                .andExpect(status().isCreated());

        // The host upsert-by-code lookup (GET ?code=) the integration client relies on still works.
        mockMvc.perform(get("/api/master-data/skus").param("code", "SKU-HOST-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(skuId));
    }
}
