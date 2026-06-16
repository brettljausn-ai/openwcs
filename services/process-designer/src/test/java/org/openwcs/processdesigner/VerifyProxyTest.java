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
                .andExpect(jsonPath("$.detail.matchedBarcode.uomCode").value("EA"))
                .andExpect(jsonPath("$.detail.uoms[0].code").value("EA"));
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
}
