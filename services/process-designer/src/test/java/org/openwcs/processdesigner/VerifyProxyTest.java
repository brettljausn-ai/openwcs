package org.openwcs.processdesigner;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.MockServerRestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Scan-verify proxy ({@code POST /verify}). The master-data resolve HTTP is mocked by binding every
 * autoconfigured {@code RestClient.Builder} to a {@link org.springframework.test.web.client.MockRestServiceServer}
 * via {@link MockServerRestClientCustomizer}; the identity-forwarding customizer still applies, so the
 * operator's {@code X-Auth-*} ride along. The same expectation is registered on every bound server;
 * only the verify service's client fires it.
 */
@Import(VerifyProxyTest.MockHttpConfig.class)
class VerifyProxyTest extends AbstractIntegrationTest {

    private static final String OPERATOR = "OPERATOR";
    private static final String WAREHOUSE = "11111111-1111-1111-1111-111111111111";

    @Autowired
    MockMvc mvc;

    @Autowired
    MockServerRestClientCustomizer mockServerCustomizer;

    @TestConfiguration
    static class MockHttpConfig {
        @Bean
        MockServerRestClientCustomizer mockServerRestClientCustomizer() {
            return new MockServerRestClientCustomizer();
        }
    }

    @BeforeEach
    void resetServers() {
        // The bound MockRestServiceServers are shared across the cached context; reset so each test
        // starts with a clean expectation set (otherwise a prior test's request blocks new expects).
        mockServerCustomizer.getServers().values().forEach(s -> s.reset());
    }

    @Test
    void verifyBarcodeReturnsNormalisedFoundAndForwardsIdentity() throws Exception {
        String body = """
            {
              "found": true,
              "matchedBarcode": { "value": "5012345", "uomId": "u1", "uomCode": "EA", "type": "EAN" },
              "sku": { "skuId": "sku-1", "code": "SKU-001", "description": "Widget", "status": "ACTIVE" },
              "uoms": [ { "uomId": "u1", "code": "EA", "baseUnit": true, "parentUomId": null, "qtyInParent": 1 } ],
              "barcodes": [ { "value": "5012345", "uomCode": "EA", "type": "EAN" } ],
              "attributeSchema": { "attributeSchemaId": "as-1", "category": "HAZMAT", "version": 2, "jsonSchema": {} }
            }
            """;
        mockServerCustomizer.getServers().values().forEach(s ->
                s.expect(ExpectedCount.manyTimes(), requestTo(Matchers.containsString(
                                "/api/master-data/resolve/sku-by-barcode")))
                        .andExpect(method(HttpMethod.GET))
                        .andExpect(header("X-Auth-User", "carol"))
                        .andRespond(withSuccess(body, MediaType.APPLICATION_JSON)));

        mvc.perform(post("/api/process-designer/verify")
                        .header("X-Auth-Roles", OPERATOR)
                        .header("X-Auth-User", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouseId\":\"" + WAREHOUSE + "\",\"kind\":\"barcode\",\"code\":\"5012345\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.id").value("sku-1"))
                .andExpect(jsonPath("$.code").value("SKU-001"))
                .andExpect(jsonPath("$.name").value("Widget"))
                .andExpect(jsonPath("$.uomCode").value("EA"))
                .andExpect(jsonPath("$.schemaCategory").value("HAZMAT"))
                .andExpect(jsonPath("$.matchedAs").value("barcode"))
                .andExpect(jsonPath("$.needsUomChoice").value(false))
                .andExpect(jsonPath("$.uoms[0].code").value("EA"))
                .andExpect(jsonPath("$.uoms[0].baseUnit").value(true))
                .andExpect(jsonPath("$.detail.matchedBarcode.uomCode").value("EA"))
                .andExpect(jsonPath("$.detail.uoms[0].code").value("EA"))
                // The authoritative per-kind fields map carries the SKU-kind values; no location-only keys.
                .andExpect(jsonPath("$.fields.id").value("sku-1"))
                .andExpect(jsonPath("$.fields.code").value("SKU-001"))
                .andExpect(jsonPath("$.fields.name").value("Widget"))
                .andExpect(jsonPath("$.fields.uomCode").value("EA"))
                .andExpect(jsonPath("$.fields.schemaCategory").value("HAZMAT"))
                .andExpect(jsonPath("$.fields.purpose").doesNotExist())
                .andExpect(jsonPath("$.fields.locationType").doesNotExist())
                .andExpect(jsonPath("$.fields.status").doesNotExist())
                // Object field "sku": the resolved SKU as a whole nested object.
                .andExpect(jsonPath("$.fields.sku.skuId").value("sku-1"))
                .andExpect(jsonPath("$.fields.sku.code").value("SKU-001"))
                .andExpect(jsonPath("$.fields.sku.description").value("Widget"))
                .andExpect(jsonPath("$.fields.sku.status").value("ACTIVE"))
                // Object field "uom": the matched (barcode-pinned) UOM resolved by uomId against uoms.
                .andExpect(jsonPath("$.fields.uom.uomId").value("u1"))
                .andExpect(jsonPath("$.fields.uom.code").value("EA"))
                .andExpect(jsonPath("$.fields.uom.factor").value(1))
                .andExpect(jsonPath("$.fields.uom.baseUnit").value(true));
    }

    @Test
    void verifyLocationReturnsLocationFieldsAndNoSkuKeys() throws Exception {
        // A location resolves a different attribute set than a SKU: purpose/locationType/status, no
        // name/uomCode/schemaCategory.
        String body = """
            {
              "found": true,
              "location": { "locationId": "loc-9", "code": "A-01-02", "locationType": "SHELF",
                            "purpose": "PICK", "status": "ACTIVE" }
            }
            """;
        mockServerCustomizer.getServers().values().forEach(s ->
                s.expect(ExpectedCount.manyTimes(), requestTo(Matchers.containsString(
                                "/api/master-data/resolve/location")))
                        .andExpect(method(HttpMethod.GET))
                        .andExpect(header("X-Auth-User", "carol"))
                        .andRespond(withSuccess(body, MediaType.APPLICATION_JSON)));

        mvc.perform(post("/api/process-designer/verify")
                        .header("X-Auth-Roles", OPERATOR)
                        .header("X-Auth-User", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouseId\":\"" + WAREHOUSE + "\",\"kind\":\"location\",\"code\":\"A-01-02\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.id").value("loc-9"))
                .andExpect(jsonPath("$.code").value("A-01-02"))
                // Location-kind fields populated.
                .andExpect(jsonPath("$.fields.id").value("loc-9"))
                .andExpect(jsonPath("$.fields.code").value("A-01-02"))
                .andExpect(jsonPath("$.fields.purpose").value("PICK"))
                .andExpect(jsonPath("$.fields.locationType").value("SHELF"))
                .andExpect(jsonPath("$.fields.status").value("ACTIVE"))
                // No SKU-only keys leak into a location result.
                .andExpect(jsonPath("$.fields.name").doesNotExist())
                .andExpect(jsonPath("$.fields.uomCode").doesNotExist())
                .andExpect(jsonPath("$.fields.schemaCategory").doesNotExist())
                // Object field "location": the resolved location as a whole nested object.
                .andExpect(jsonPath("$.fields.location.locationId").value("loc-9"))
                .andExpect(jsonPath("$.fields.location.code").value("A-01-02"))
                .andExpect(jsonPath("$.fields.location.locationType").value("SHELF"))
                .andExpect(jsonPath("$.fields.location.purpose").value("PICK"))
                .andExpect(jsonPath("$.fields.location.status").value("ACTIVE"))
                // No SKU object on a location result.
                .andExpect(jsonPath("$.fields.sku").doesNotExist())
                .andExpect(jsonPath("$.fields.uom").doesNotExist());
    }

    @Test
    void verifyNotFoundIsCleanPassthrough() throws Exception {
        mockServerCustomizer.getServers().values().forEach(s ->
                s.expect(ExpectedCount.manyTimes(), requestTo(Matchers.containsString(
                                "/api/master-data/resolve/sku-by-barcode")))
                        .andExpect(method(HttpMethod.GET))
                        .andRespond(withSuccess("{\"found\":false}", MediaType.APPLICATION_JSON)));

        mvc.perform(post("/api/process-designer/verify")
                        .header("X-Auth-Roles", OPERATOR)
                        .header("X-Auth-User", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouseId\":\"" + WAREHOUSE + "\",\"kind\":\"barcode\",\"code\":\"nope\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(false))
                .andExpect(jsonPath("$.id").value(Matchers.nullValue()));
    }

    // --- skuScan: combined barcode-or-SKU-code resolve with UOM-choice signalling --------------------

    @Test
    void skuScanMatchedByBarcodePinsUomNoChoice() throws Exception {
        // The value matches a product barcode: master-data sku-by-barcode resolves -> matchedAs=barcode,
        // uom pinned by the barcode, no prompt.
        String body = """
            {
              "found": true,
              "matchedBarcode": { "value": "5012345", "uomId": "u2", "uomCode": "CASE", "type": "EAN" },
              "sku": { "skuId": "sku-1", "code": "SKU-001", "description": "Widget", "status": "ACTIVE" },
              "uoms": [
                { "uomId": "u1", "code": "EA", "baseUnit": true, "parentUomId": null, "qtyInParent": 1 },
                { "uomId": "u2", "code": "CASE", "baseUnit": false, "parentUomId": "u1", "qtyInParent": 12 }
              ],
              "barcodes": [ { "value": "5012345", "uomCode": "CASE", "type": "EAN" } ],
              "attributeSchema": { "attributeSchemaId": "as-1", "category": "GENERAL", "version": 1, "jsonSchema": {} }
            }
            """;
        mockServerCustomizer.getServers().values().forEach(s ->
                s.expect(ExpectedCount.manyTimes(), requestTo(Matchers.containsString(
                                "/api/master-data/resolve/sku-by-barcode")))
                        .andExpect(method(HttpMethod.GET))
                        .andExpect(header("X-Auth-User", "carol"))
                        .andRespond(withSuccess(body, MediaType.APPLICATION_JSON)));

        mvc.perform(post("/api/process-designer/verify")
                        .header("X-Auth-Roles", OPERATOR)
                        .header("X-Auth-User", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouseId\":\"" + WAREHOUSE + "\",\"kind\":\"skuScan\",\"code\":\"5012345\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.matchedAs").value("barcode"))
                .andExpect(jsonPath("$.id").value("sku-1"))
                .andExpect(jsonPath("$.uomCode").value("CASE"))
                .andExpect(jsonPath("$.needsUomChoice").value(false))
                .andExpect(jsonPath("$.uoms.length()").value(2))
                // skuScan resolves the SKU field set; name/uomCode/schemaCategory populated.
                .andExpect(jsonPath("$.fields.id").value("sku-1"))
                .andExpect(jsonPath("$.fields.code").value("SKU-001"))
                .andExpect(jsonPath("$.fields.name").value("Widget"))
                .andExpect(jsonPath("$.fields.uomCode").value("CASE"))
                .andExpect(jsonPath("$.fields.schemaCategory").value("GENERAL"))
                .andExpect(jsonPath("$.fields.purpose").doesNotExist())
                // Object field "uom": the matched UOM (CASE, uomId u2) with factor = qtyInParent (12).
                .andExpect(jsonPath("$.fields.uom.uomId").value("u2"))
                .andExpect(jsonPath("$.fields.uom.code").value("CASE"))
                .andExpect(jsonPath("$.fields.uom.factor").value(12))
                .andExpect(jsonPath("$.fields.uom.baseUnit").value(false))
                // Object field "sku": the resolved SKU as a whole nested object.
                .andExpect(jsonPath("$.fields.sku.skuId").value("sku-1"))
                .andExpect(jsonPath("$.fields.sku.code").value("SKU-001"))
                .andExpect(jsonPath("$.fields.sku.description").value("Widget"))
                .andExpect(jsonPath("$.fields.sku.status").value("ACTIVE"));
    }

    @Test
    void skuScanMatchedBySkuCodeWithMultipleUomsNeedsChoice() throws Exception {
        // Not a barcode -> sku-by-barcode found:false; then matches a SKU code with 2 UOMs ->
        // matchedAs=sku, uomCode null, needsUomChoice=true, uoms lists both.
        String skuBody = """
            {
              "found": true,
              "matchedBarcode": null,
              "sku": { "skuId": "sku-2", "code": "SKU-002", "description": "Gadget", "status": "ACTIVE" },
              "uoms": [
                { "uomId": "u1", "code": "EA", "baseUnit": true, "parentUomId": null, "qtyInParent": 1 },
                { "uomId": "u2", "code": "CASE", "baseUnit": false, "parentUomId": "u1", "qtyInParent": 6 }
              ],
              "barcodes": [],
              "attributeSchema": { "attributeSchemaId": "as-1", "category": "GENERAL", "version": 1, "jsonSchema": {} }
            }
            """;
        mockServerCustomizer.getServers().values().forEach(s -> {
            s.expect(ExpectedCount.manyTimes(), requestTo(Matchers.containsString(
                            "/api/master-data/resolve/sku-by-barcode")))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess("{\"found\":false}", MediaType.APPLICATION_JSON));
            s.expect(ExpectedCount.manyTimes(), requestTo(Matchers.allOf(
                            Matchers.containsString("/api/master-data/resolve/sku"),
                            Matchers.not(Matchers.containsString("sku-by-barcode")))))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(skuBody, MediaType.APPLICATION_JSON));
        });

        mvc.perform(post("/api/process-designer/verify")
                        .header("X-Auth-Roles", OPERATOR)
                        .header("X-Auth-User", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouseId\":\"" + WAREHOUSE + "\",\"kind\":\"skuScan\",\"code\":\"SKU-002\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.matchedAs").value("sku"))
                .andExpect(jsonPath("$.id").value("sku-2"))
                .andExpect(jsonPath("$.uomCode").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.needsUomChoice").value(true))
                .andExpect(jsonPath("$.uoms.length()").value(2))
                .andExpect(jsonPath("$.uoms[0].code").value("EA"))
                .andExpect(jsonPath("$.uoms[1].code").value("CASE"));
    }

    @Test
    void skuScanMatchedBySkuCodeWithSingleUomAutoPicks() throws Exception {
        // Not a barcode; matches a SKU code with exactly 1 UOM -> matchedAs=sku, uomCode set,
        // needsUomChoice=false.
        String skuBody = """
            {
              "found": true,
              "matchedBarcode": null,
              "sku": { "skuId": "sku-3", "code": "SKU-003", "description": "Gizmo", "status": "ACTIVE" },
              "uoms": [ { "uomId": "u1", "code": "EA", "baseUnit": true, "parentUomId": null, "qtyInParent": 1 } ],
              "barcodes": [],
              "attributeSchema": { "attributeSchemaId": "as-1", "category": "GENERAL", "version": 1, "jsonSchema": {} }
            }
            """;
        mockServerCustomizer.getServers().values().forEach(s -> {
            s.expect(ExpectedCount.manyTimes(), requestTo(Matchers.containsString(
                            "/api/master-data/resolve/sku-by-barcode")))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess("{\"found\":false}", MediaType.APPLICATION_JSON));
            s.expect(ExpectedCount.manyTimes(), requestTo(Matchers.allOf(
                            Matchers.containsString("/api/master-data/resolve/sku"),
                            Matchers.not(Matchers.containsString("sku-by-barcode")))))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(skuBody, MediaType.APPLICATION_JSON));
        });

        mvc.perform(post("/api/process-designer/verify")
                        .header("X-Auth-Roles", OPERATOR)
                        .header("X-Auth-User", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouseId\":\"" + WAREHOUSE + "\",\"kind\":\"skuScan\",\"code\":\"SKU-003\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.matchedAs").value("sku"))
                .andExpect(jsonPath("$.id").value("sku-3"))
                .andExpect(jsonPath("$.uomCode").value("EA"))
                .andExpect(jsonPath("$.needsUomChoice").value(false))
                .andExpect(jsonPath("$.uoms.length()").value(1));
    }

    // --- order / asn: resolve an order/picksheet or ASN barcode via order-management -----------------

    @Test
    void verifyOrderReturnsOrderFieldsAndObjectAndForwardsIdentity() throws Exception {
        String body = """
            {
              "found": true,
              "order": { "orderId": "ord-1", "orderRef": "SO-1001", "orderType": "OUTBOUND",
                         "status": "RELEASED", "customerRef": "ACME-42", "lineCount": 3 }
            }
            """;
        mockServerCustomizer.getServers().values().forEach(s ->
                s.expect(ExpectedCount.manyTimes(), requestTo(Matchers.containsString(
                                "/api/orders/resolve")))
                        .andExpect(method(HttpMethod.GET))
                        .andExpect(header("X-Auth-User", "carol"))
                        .andRespond(withSuccess(body, MediaType.APPLICATION_JSON)));

        mvc.perform(post("/api/process-designer/verify")
                        .header("X-Auth-Roles", OPERATOR)
                        .header("X-Auth-User", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouseId\":\"" + WAREHOUSE + "\",\"kind\":\"order\",\"code\":\"SO-1001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.id").value("ord-1"))
                .andExpect(jsonPath("$.code").value("SO-1001"))
                .andExpect(jsonPath("$.name").value("ACME-42"))
                .andExpect(jsonPath("$.matchedAs").value(Matchers.nullValue()))
                // Order-kind scalars.
                .andExpect(jsonPath("$.fields.id").value("ord-1"))
                .andExpect(jsonPath("$.fields.code").value("SO-1001"))
                .andExpect(jsonPath("$.fields.status").value("RELEASED"))
                .andExpect(jsonPath("$.fields.orderType").value("OUTBOUND"))
                .andExpect(jsonPath("$.fields.customerRef").value("ACME-42"))
                .andExpect(jsonPath("$.fields.lineCount").value(3))
                // No SKU/location keys leak.
                .andExpect(jsonPath("$.fields.uomCode").doesNotExist())
                .andExpect(jsonPath("$.fields.purpose").doesNotExist())
                // Object field "order".
                .andExpect(jsonPath("$.fields.order.orderId").value("ord-1"))
                .andExpect(jsonPath("$.fields.order.orderRef").value("SO-1001"))
                .andExpect(jsonPath("$.fields.order.orderType").value("OUTBOUND"))
                .andExpect(jsonPath("$.fields.order.status").value("RELEASED"))
                .andExpect(jsonPath("$.fields.order.customerRef").value("ACME-42"))
                .andExpect(jsonPath("$.fields.order.lineCount").value(3))
                // The asn object key is not present on an order result.
                .andExpect(jsonPath("$.fields.asn").doesNotExist());
    }

    @Test
    void verifyAsnReturnsAsnObjectAndForwardsIdentity() throws Exception {
        String body = """
            {
              "found": true,
              "order": { "orderId": "ord-9", "orderRef": "ASN-7", "orderType": "INBOUND",
                         "status": "EXPECTED", "customerRef": "VEND-1", "lineCount": 5 }
            }
            """;
        mockServerCustomizer.getServers().values().forEach(s ->
                s.expect(ExpectedCount.manyTimes(), requestTo(Matchers.containsString(
                                "/api/orders/resolve")))
                        .andExpect(method(HttpMethod.GET))
                        .andExpect(header("X-Auth-User", "carol"))
                        .andRespond(withSuccess(body, MediaType.APPLICATION_JSON)));

        mvc.perform(post("/api/process-designer/verify")
                        .header("X-Auth-Roles", OPERATOR)
                        .header("X-Auth-User", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouseId\":\"" + WAREHOUSE + "\",\"kind\":\"asn\",\"code\":\"ASN-7\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.id").value("ord-9"))
                .andExpect(jsonPath("$.code").value("ASN-7"))
                .andExpect(jsonPath("$.matchedAs").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.fields.orderType").value("INBOUND"))
                .andExpect(jsonPath("$.fields.lineCount").value(5))
                // Object field "asn" (not "order") on the asn kind.
                .andExpect(jsonPath("$.fields.asn.orderId").value("ord-9"))
                .andExpect(jsonPath("$.fields.asn.orderRef").value("ASN-7"))
                .andExpect(jsonPath("$.fields.asn.orderType").value("INBOUND"))
                .andExpect(jsonPath("$.fields.asn.status").value("EXPECTED"))
                .andExpect(jsonPath("$.fields.asn.customerRef").value("VEND-1"))
                .andExpect(jsonPath("$.fields.asn.lineCount").value(5))
                .andExpect(jsonPath("$.fields.order").doesNotExist());
    }

    @Test
    void verifyOrderNotFoundIsCleanPassthrough() throws Exception {
        mockServerCustomizer.getServers().values().forEach(s ->
                s.expect(ExpectedCount.manyTimes(), requestTo(Matchers.containsString(
                                "/api/orders/resolve")))
                        .andExpect(method(HttpMethod.GET))
                        .andRespond(withSuccess("{\"found\":false}", MediaType.APPLICATION_JSON)));

        mvc.perform(post("/api/process-designer/verify")
                        .header("X-Auth-Roles", OPERATOR)
                        .header("X-Auth-User", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouseId\":\"" + WAREHOUSE + "\",\"kind\":\"order\",\"code\":\"nope\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(false))
                .andExpect(jsonPath("$.id").value(Matchers.nullValue()));
    }

    @Test
    void skuScanNoMatchIsCleanPassthrough() throws Exception {
        // Neither a barcode nor a SKU code matches -> found:false.
        mockServerCustomizer.getServers().values().forEach(s -> {
            s.expect(ExpectedCount.manyTimes(), requestTo(Matchers.containsString(
                            "/api/master-data/resolve/sku-by-barcode")))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess("{\"found\":false}", MediaType.APPLICATION_JSON));
            s.expect(ExpectedCount.manyTimes(), requestTo(Matchers.allOf(
                            Matchers.containsString("/api/master-data/resolve/sku"),
                            Matchers.not(Matchers.containsString("sku-by-barcode")))))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess("{\"found\":false}", MediaType.APPLICATION_JSON));
        });

        mvc.perform(post("/api/process-designer/verify")
                        .header("X-Auth-Roles", OPERATOR)
                        .header("X-Auth-User", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouseId\":\"" + WAREHOUSE + "\",\"kind\":\"skuScan\",\"code\":\"nope\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(false))
                .andExpect(jsonPath("$.matchedAs").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.needsUomChoice").value(false))
                .andExpect(jsonPath("$.uoms.length()").value(0));
    }
}
