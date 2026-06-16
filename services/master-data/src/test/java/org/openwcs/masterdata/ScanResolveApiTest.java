package org.openwcs.masterdata;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
 * Verifies the read-only "resolve a scanned code" endpoints used for process-designer scan
 * verification: barcode → SKU graph, SKU-code → SKU graph, and warehouse location resolve. A miss
 * always returns HTTP 200 with {@code found:false} so a process flow can branch on it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ScanResolveApiTest {

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

    String warehouseId;
    String skuId;
    String eaUomId;
    String csUomId;
    String schemaId;

    @BeforeEach
    void seed() throws Exception {
        // Unique codes per run so repeated test methods do not collide on the unique SKU/warehouse codes.
        String tag = UUID.randomUUID().toString().substring(0, 8);

        warehouseId = id(post("/api/master-data/warehouses",
                Map.of("code", "WH-RES-" + tag, "name", "Resolve DC")));

        skuId = id(post("/api/master-data/skus",
                Map.of("code", "SKU-RES-" + tag, "description", "Resolve tee", "status", "ACTIVE")));

        // Two UOMs: base EA, and CASE as its parent.
        eaUomId = id(post("/api/master-data/skus/" + skuId + "/uoms",
                Map.of("code", "EA", "baseUnit", true)));
        csUomId = id(post("/api/master-data/skus/" + skuId + "/uoms",
                Map.of("code", "CS", "baseUnit", false, "qtyInParent", 1)));

        // Two barcodes, one per packaging level.
        post("/api/master-data/skus/" + skuId + "/barcodes",
                Map.of("value", "BC-EA-" + tag, "uomId", eaUomId)).andExpect(status().isCreated());
        post("/api/master-data/skus/" + skuId + "/barcodes",
                Map.of("value", "BC-CS-" + tag, "uomId", csUomId)).andExpect(status().isCreated());

        // Warehouse attribute schema for SKUs, linked through the warehouse-scoped SkuProfile.
        Map<String, Object> jsonSchema = Map.of("type", "object", "properties",
                Map.of("brand", Map.of("type", "string")));
        schemaId = id(post("/api/master-data/attribute-schemas", new HashMap<>(Map.of(
                "warehouseId", warehouseId,
                "appliesTo", "SKU",
                "category", "apparel",
                "schemaVersion", 3,
                "jsonSchema", jsonSchema))));

        put("/api/master-data/skus/" + skuId + "/profiles", new HashMap<>(Map.of(
                "warehouseId", warehouseId,
                "attributeSchemaId", schemaId,
                "metadata", Map.of("brand", "Acme"))))
                .andExpect(status().isOk());

        // A location to resolve by (warehouse, code).
        post("/api/master-data/locations", new HashMap<>(Map.of(
                "warehouseId", warehouseId,
                "code", "PICK-A-01-" + tag,
                "locationType", "BIN",
                "purpose", "PICK",
                "status", "ACTIVE")))
                .andExpect(status().isCreated());

        // Stash the unique tag for use in the test bodies.
        this.tag = tag;
    }

    String tag;

    @Test
    void skuByBarcodeReturnsFullLinkedGraph() throws Exception {
        mockMvc.perform(get("/api/master-data/resolve/sku-by-barcode")
                        .param("warehouseId", warehouseId)
                        .param("code", "BC-EA-" + tag))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.ambiguous").value(false))
                .andExpect(jsonPath("$.matchedBarcode.value").value("BC-EA-" + tag))
                .andExpect(jsonPath("$.matchedBarcode.uomId").value(eaUomId))
                .andExpect(jsonPath("$.matchedBarcode.uomCode").value("EA"))
                .andExpect(jsonPath("$.sku.skuId").value(skuId))
                .andExpect(jsonPath("$.sku.code").value("SKU-RES-" + tag))
                .andExpect(jsonPath("$.sku.status").value("ACTIVE"))
                // Both UOMs present.
                .andExpect(jsonPath("$.uoms.length()").value(2))
                .andExpect(jsonPath("$.uoms[?(@.code=='EA')].baseUnit").value(true))
                .andExpect(jsonPath("$.uoms[?(@.code=='CS')]").exists())
                // Both barcodes present.
                .andExpect(jsonPath("$.barcodes.length()").value(2))
                .andExpect(jsonPath("$.barcodes[?(@.value=='BC-EA-" + tag + "')].uomCode").value("EA"))
                .andExpect(jsonPath("$.barcodes[?(@.value=='BC-CS-" + tag + "')].uomCode").value("CS"))
                // Attribute schema via the warehouse profile.
                .andExpect(jsonPath("$.attributeSchema.attributeSchemaId").value(schemaId))
                .andExpect(jsonPath("$.attributeSchema.category").value("apparel"))
                .andExpect(jsonPath("$.attributeSchema.version").value(3))
                .andExpect(jsonPath("$.attributeSchema.jsonSchema.type").value("object"));
    }

    @Test
    void unknownBarcodeReturnsFoundFalseWith200() throws Exception {
        mockMvc.perform(get("/api/master-data/resolve/sku-by-barcode")
                        .param("warehouseId", warehouseId)
                        .param("code", "NO-SUCH-BARCODE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(false))
                .andExpect(jsonPath("$.matchedBarcode").doesNotExist())
                .andExpect(jsonPath("$.sku").doesNotExist())
                .andExpect(jsonPath("$.uoms.length()").value(0))
                .andExpect(jsonPath("$.barcodes.length()").value(0))
                .andExpect(jsonPath("$.attributeSchema").doesNotExist());
    }

    @Test
    void skuByCodeResolvesGraphWithNullMatchedBarcode() throws Exception {
        mockMvc.perform(get("/api/master-data/resolve/sku")
                        .param("warehouseId", warehouseId)
                        .param("code", "SKU-RES-" + tag))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.matchedBarcode").doesNotExist())
                .andExpect(jsonPath("$.sku.skuId").value(skuId))
                .andExpect(jsonPath("$.uoms.length()").value(2))
                .andExpect(jsonPath("$.barcodes.length()").value(2))
                .andExpect(jsonPath("$.attributeSchema.attributeSchemaId").value(schemaId));
    }

    @Test
    void unknownSkuCodeReturnsFoundFalseWith200() throws Exception {
        mockMvc.perform(get("/api/master-data/resolve/sku")
                        .param("warehouseId", warehouseId)
                        .param("code", "NO-SUCH-SKU"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(false))
                .andExpect(jsonPath("$.sku").doesNotExist());
    }

    @Test
    void locationResolvesByWarehouseAndCode() throws Exception {
        mockMvc.perform(get("/api/master-data/resolve/location")
                        .param("warehouseId", warehouseId)
                        .param("code", "PICK-A-01-" + tag))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.location.code").value("PICK-A-01-" + tag))
                .andExpect(jsonPath("$.location.locationType").value("BIN"))
                .andExpect(jsonPath("$.location.purpose").value("PICK"))
                .andExpect(jsonPath("$.location.status").value("ACTIVE"))
                .andExpect(jsonPath("$.location.locationId").exists());
    }

    @Test
    void unknownLocationReturnsFoundFalseWith200() throws Exception {
        mockMvc.perform(get("/api/master-data/resolve/location")
                        .param("warehouseId", warehouseId)
                        .param("code", "NO-SUCH-LOCATION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(false))
                .andExpect(jsonPath("$.location").doesNotExist());
    }

    // ------------------------------------------------------------------ helpers

    private org.springframework.test.web.servlet.ResultActions post(String url, Map<String, ?> body) throws Exception {
        return mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)));
    }

    private org.springframework.test.web.servlet.ResultActions put(String url, Map<String, ?> body) throws Exception {
        return mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)));
    }

    private String id(org.springframework.test.web.servlet.ResultActions created) throws Exception {
        JsonNode node = om.readTree(created.andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        return node.get("id").asText();
    }
}
