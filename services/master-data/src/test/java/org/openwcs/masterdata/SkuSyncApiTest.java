package org.openwcs.masterdata;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
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
 * Host-driven SKU sync (build.md §6, §16): the host pushes a list of SKUs carrying their UoM
 * hierarchy and barcodes inline, referencing parent UoM / barcode UoM by code. The host is
 * authoritative, so a re-sync fully replaces the SKU's stored UoMs and barcodes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SkuSyncApiTest {

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

    @Test
    void syncsSkuWithNestedUomHierarchyAndBarcodes() throws Exception {
        var payload = List.of(Map.of(
                "code", "SKU-SYNC-1",
                "description", "Widget",
                "batchTracked", true,
                "uoms", List.of(
                        Map.of("code", "EACH", "baseUnit", true),
                        Map.of("code", "CASE", "parentCode", "EACH", "qtyInParent", 12)),
                "barcodes", List.of(
                        Map.of("value", "4006381333931", "uomCode", "EACH", "type", "EAN13"))));

        mockMvc.perform(post("/api/master-data/skus/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(1))
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.results[0].action").value("CREATED"))
                .andExpect(jsonPath("$.results[0].uoms").value(2))
                .andExpect(jsonPath("$.results[0].barcodes").value(1));

        String skuId = idByCode("SKU-SYNC-1");

        // The base unit and the CASE→EACH parent link resolved by code.
        String uoms = mockMvc.perform(get("/api/master-data/skus/{id}/uoms", skuId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var tree = om.readTree(uoms);
        String eachId = null;
        for (var node : tree) {
            if ("EACH".equals(node.get("code").asText())) {
                eachId = node.get("id").asText();
            }
        }
        org.junit.jupiter.api.Assertions.assertNotNull(eachId);
        for (var node : tree) {
            if ("CASE".equals(node.get("code").asText())) {
                org.junit.jupiter.api.Assertions.assertEquals(eachId, node.get("parentUomId").asText());
            }
        }

        // The barcode bound to the EACH UoM and the EAN13 type.
        mockMvc.perform(get("/api/master-data/skus/{id}/barcodes", skuId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].value").value("4006381333931"))
                .andExpect(jsonPath("$[0].uomId").value(eachId))
                .andExpect(jsonPath("$[0].barcodeTypeId").value(Matchers.notNullValue()));
    }

    @Test
    void reSyncFullyReplacesUomsAndBarcodes() throws Exception {
        var first = List.of(Map.of(
                "code", "SKU-SYNC-2",
                "uoms", List.of(
                        Map.of("code", "EACH", "baseUnit", true),
                        Map.of("code", "INNER", "parentCode", "EACH", "qtyInParent", 6)),
                "barcodes", List.of(
                        Map.of("value", "1111111111116", "uomCode", "EACH", "type", "EAN13"),
                        Map.of("value", "2222222222229", "uomCode", "INNER", "type", "EAN13"))));
        mockMvc.perform(post("/api/master-data/skus/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(first)))
                .andExpect(status().isOk());
        String skuId = idByCode("SKU-SYNC-2");

        // Re-sync: drop INNER, keep EACH, and replace the barcode set entirely.
        var second = List.of(Map.of(
                "code", "SKU-SYNC-2",
                "description", "now described",
                "uoms", List.of(Map.of("code", "EACH", "baseUnit", true)),
                "barcodes", List.of(Map.of("value", "3333333333332", "uomCode", "EACH", "type", "EAN13"))));
        mockMvc.perform(post("/api/master-data/skus/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(second)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.results[0].action").value("UPDATED"));

        // Only EACH remains; INNER is gone.
        mockMvc.perform(get("/api/master-data/skus/{id}/uoms", skuId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].code").value("EACH"));

        // Old barcodes gone, only the new one stored.
        mockMvc.perform(get("/api/master-data/skus/{id}/barcodes", skuId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].value").value("3333333333332"));
    }

    @Test
    void skuAbsentFromAlaterBatchKeepsItsUomsAndBarcodes() throws Exception {
        // Seed a SKU with a UoM + barcode.
        var seed = List.of(Map.of(
                "code", "SKU-KEEP-1",
                "uoms", List.of(Map.of("code", "EACH", "baseUnit", true)),
                "barcodes", List.of(Map.of("value", "9111111111119", "uomCode", "EACH", "type", "EAN13"))));
        mockMvc.perform(post("/api/master-data/skus/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(seed)))
                .andExpect(status().isOk());
        String keepId = idByCode("SKU-KEEP-1");

        // A later batch syncs a *different* SKU and does not mention SKU-KEEP-1 at all.
        var other = List.of(Map.of(
                "code", "SKU-KEEP-OTHER",
                "uoms", List.of(Map.of("code", "EACH", "baseUnit", true))));
        mockMvc.perform(post("/api/master-data/skus/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(other)))
                .andExpect(status().isOk());

        // SKU-KEEP-1 is untouched: still present, and its UoM + barcode are intact (not removed).
        mockMvc.perform(get("/api/master-data/skus/{id}/uoms", keepId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].code").value("EACH"));
        mockMvc.perform(get("/api/master-data/skus/{id}/barcodes", keepId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].value").value("9111111111119"));
    }

    @Test
    void interactiveSyncIsForbidden() throws Exception {
        mockMvc.perform(post("/api/master-data/skus/sync")
                        .header(INTERACTIVE, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(List.of(Map.of("code", "SKU-SYNC-NO")))))
                .andExpect(status().isForbidden());
    }

    private String idByCode(String code) throws Exception {
        String body = mockMvc.perform(get("/api/master-data/skus").param("code", code))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return om.readTree(body).get("content").get(0).get("id").asText();
    }
}
