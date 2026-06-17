package org.openwcs.processdesigner;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Default config ({@link AbstractIntegrationTest}: scripting OFF, no Anthropic key):
 * <ul>
 *   <li>{@code POST /assist/task} returns 503 "AI assist not configured" (context still started);</li>
 *   <li>{@code GET /capabilities} reflects scriptingEnabled=false, aiAssistEnabled=false; canAuthorScript
 *       follows the caller's permission (ADMIN true, SUPERVISOR false).</li>
 * </ul>
 */
class AssistAndCapabilitiesDefaultTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void assistReturns503WhenNotConfigured() throws Exception {
        mvc.perform(post("/api/process-designer/assist/task")
                        .header("X-Auth-Roles", "SUPERVISOR")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"do a thing\",\"variables\":[]}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("AI assist not configured"));
    }

    @Test
    void capabilitiesReflectFlagAndPermission() throws Exception {
        // Scripting off + no key; ADMIN holds PROCESS_SCRIPT_AUTHOR.
        mvc.perform(get("/api/process-designer/capabilities").header("X-Auth-Roles", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scriptingEnabled").value(false))
                .andExpect(jsonPath("$.aiAssistEnabled").value(false))
                .andExpect(jsonPath("$.canAuthorScript").value(true))
                // Server-driven scan-verify kinds for the designer's Verify picker.
                .andExpect(jsonPath("$.verifyKinds").isArray())
                .andExpect(jsonPath("$.verifyKinds[0]").value("barcode"))
                .andExpect(jsonPath("$.verifyKinds[1]").value("sku"))
                .andExpect(jsonPath("$.verifyKinds[2]").value("location"))
                .andExpect(jsonPath("$.verifyKinds[3]").value("skuScan"))
                .andExpect(jsonPath("$.verifyKinds[4]").value("order"))
                .andExpect(jsonPath("$.verifyKinds[5]").value("asn"))
                .andExpect(jsonPath("$.verifyKinds",
                        org.hamcrest.Matchers.hasItems("skuScan", "order", "asn")))
                // Per-kind resolvable-field catalog: location vs SKU resolve different attributes.
                // Scalars plus object fields (object:true with a sub drill-down list).
                .andExpect(jsonPath("$.verifyFields.location").isArray())
                .andExpect(jsonPath("$.verifyFields.location[*].key",
                        org.hamcrest.Matchers.containsInAnyOrder(
                                "id", "code", "purpose", "locationType", "status", "location")))
                .andExpect(jsonPath("$.verifyFields.location[0].key").value("id"))
                .andExpect(jsonPath("$.verifyFields.location[0].label").value("Location ID"))
                // Object field "location" on the location kind, with its sub drill-down fields.
                .andExpect(jsonPath(
                        "$.verifyFields.location[?(@.key=='location')].object").value(
                        org.hamcrest.Matchers.hasItem(true)))
                .andExpect(jsonPath("$.verifyFields.location[?(@.key=='location')].sub[*].key",
                        org.hamcrest.Matchers.containsInAnyOrder(
                                "locationId", "code", "locationType", "purpose", "status")))
                .andExpect(jsonPath("$.verifyFields.sku[*].key",
                        org.hamcrest.Matchers.containsInAnyOrder(
                                "id", "code", "name", "uomCode", "schemaCategory", "sku", "uom")))
                .andExpect(jsonPath("$.verifyFields.sku[2].key").value("name"))
                .andExpect(jsonPath("$.verifyFields.sku[2].label").value("Description"))
                // Scalar fields omit object/sub (NON_DEFAULT/NON_EMPTY).
                .andExpect(jsonPath("$.verifyFields.sku[0].object").doesNotExist())
                .andExpect(jsonPath("$.verifyFields.sku[0].sub").doesNotExist())
                // Object field "uom" on a SKU kind: object:true, sub = uomId/code/factor/baseUnit.
                .andExpect(jsonPath("$.verifyFields.sku[?(@.key=='uom')].object").value(
                        org.hamcrest.Matchers.hasItem(true)))
                .andExpect(jsonPath("$.verifyFields.sku[?(@.key=='uom')].label").value(
                        org.hamcrest.Matchers.hasItem("Unit of measure (object)")))
                .andExpect(jsonPath("$.verifyFields.sku[?(@.key=='uom')].sub[*].key",
                        org.hamcrest.Matchers.containsInAnyOrder(
                                "uomId", "code", "factor", "baseUnit")))
                // Object field "sku" on a SKU kind: object:true, sub = skuId/code/description/status.
                .andExpect(jsonPath("$.verifyFields.sku[?(@.key=='sku')].object").value(
                        org.hamcrest.Matchers.hasItem(true)))
                .andExpect(jsonPath("$.verifyFields.sku[?(@.key=='sku')].sub[*].key",
                        org.hamcrest.Matchers.containsInAnyOrder(
                                "skuId", "code", "description", "status")))
                // barcode and skuScan share the SKU field set (incl. the object fields).
                .andExpect(jsonPath("$.verifyFields.barcode[*].key",
                        org.hamcrest.Matchers.containsInAnyOrder(
                                "id", "code", "name", "uomCode", "schemaCategory", "sku", "uom")))
                .andExpect(jsonPath("$.verifyFields.skuScan[*].key",
                        org.hamcrest.Matchers.containsInAnyOrder(
                                "id", "code", "name", "uomCode", "schemaCategory", "sku", "uom")))
                .andExpect(jsonPath("$.verifyFields.skuScan[?(@.key=='uom')].sub[*].key",
                        org.hamcrest.Matchers.containsInAnyOrder(
                                "uomId", "code", "factor", "baseUnit")))
                // order kind: scalars + object "order" with its sub drill-down fields.
                .andExpect(jsonPath("$.verifyFields.order[*].key",
                        org.hamcrest.Matchers.containsInAnyOrder(
                                "id", "code", "status", "orderType", "customerRef", "lineCount", "order")))
                .andExpect(jsonPath("$.verifyFields.order[?(@.key=='order')].object").value(
                        org.hamcrest.Matchers.hasItem(true)))
                .andExpect(jsonPath("$.verifyFields.order[?(@.key=='order')].label").value(
                        org.hamcrest.Matchers.hasItem("Order (object)")))
                .andExpect(jsonPath("$.verifyFields.order[?(@.key=='order')].sub[*].key",
                        org.hamcrest.Matchers.containsInAnyOrder(
                                "orderId", "orderRef", "orderType", "status", "customerRef", "lineCount")))
                // asn kind: same shape, object key "asn", inbound-phrased labels.
                .andExpect(jsonPath("$.verifyFields.asn[*].key",
                        org.hamcrest.Matchers.containsInAnyOrder(
                                "id", "code", "status", "orderType", "customerRef", "lineCount", "asn")))
                .andExpect(jsonPath("$.verifyFields.asn[?(@.key=='code')].label").value(
                        org.hamcrest.Matchers.hasItem("ASN reference")))
                .andExpect(jsonPath("$.verifyFields.asn[?(@.key=='asn')].object").value(
                        org.hamcrest.Matchers.hasItem(true)))
                .andExpect(jsonPath("$.verifyFields.asn[?(@.key=='asn')].label").value(
                        org.hamcrest.Matchers.hasItem("ASN (object)")))
                .andExpect(jsonPath("$.verifyFields.asn[?(@.key=='asn')].sub[*].key",
                        org.hamcrest.Matchers.containsInAnyOrder(
                                "orderId", "orderRef", "orderType", "status", "customerRef", "lineCount")));

        // SUPERVISOR can view (PROCESS_DESIGN_VIEW) but cannot author scripts.
        mvc.perform(get("/api/process-designer/capabilities").header("X-Auth-Roles", "SUPERVISOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canAuthorScript").value(false));
    }
}
