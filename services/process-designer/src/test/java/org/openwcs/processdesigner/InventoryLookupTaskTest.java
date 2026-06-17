package org.openwcs.processdesigner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
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
 * {@code inventory.lookup} reads on-hand stock for the scanned SKU at a location OR on a handling
 * unit, with the warehouse auto-injected from the running instance (so the operator never scans it).
 * Downstream inventory HTTP is mocked on every bound {@code RestClient} (see {@link NewTaskTypeTest}).
 */
@Import(InventoryLookupTaskTest.MockHttpConfig.class)
class InventoryLookupTaskTest extends AbstractIntegrationTest {

    private static final String EDITOR = "SUPERVISOR";
    private static final String OPERATOR = "OPERATOR";
    private static final String KEY = "lookup-flow";
    private static final String WAREHOUSE = "11111111-1111-1111-1111-111111111111";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MockServerRestClientCustomizer mockServerCustomizer;

    @TestConfiguration
    static class MockHttpConfig {
        @Bean
        MockServerRestClientCustomizer mockServerRestClientCustomizer() {
            return new MockServerRestClientCustomizer();
        }
    }

    // One method: the shared MockRestServiceServer rejects new expectations once any request has run,
    // so both lookups (HU + location) register their stubs up front, then execute.
    @Test
    void lookupOnHandByHandlingUnitAndByLocation() throws Exception {
        UUID sku = UUID.randomUUID();
        UUID hu = UUID.randomUUID();
        UUID loc = UUID.randomUUID();
        publishLookupFlow();

        // HU contents: two lines of our SKU (3 + 4) and one of another SKU -> onHand 7.
        String contents = "[{\"skuId\":\"" + sku + "\",\"qty\":3},"
                + "{\"skuId\":\"" + sku + "\",\"qty\":4},"
                + "{\"skuId\":\"" + UUID.randomUUID() + "\",\"qty\":9}]";
        // Location availability: the URL must carry the instance's warehouse (auto-injected, not mapped).
        mockServerCustomizer.getServers().values().forEach(s -> {
            s.expect(ExpectedCount.manyTimes(),
                            requestTo("http://localhost:8082/api/inventory/handling-units/" + hu + "/contents"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(contents, MediaType.APPLICATION_JSON));
            s.expect(ExpectedCount.manyTimes(), requestTo(
                            "http://localhost:8082/api/inventory/availability?warehouseId=" + WAREHOUSE
                                    + "&skuId=" + sku + "&locationId=" + loc))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess("{\"onHand\":12,\"reserved\":0,\"available\":12}",
                            MediaType.APPLICATION_JSON));
        });

        // By handling unit -> summed across the SKU's lines.
        JsonNode huData = runLookup("{\"skuId\":\"" + sku + "\",\"huId\":\"" + hu + "\"}");
        assertThat(huData.get("expectedQty").decimalValue().intValueExact()).isEqualTo(7);

        // By location -> warehouse auto-injected into the availability call.
        JsonNode locData = runLookup("{\"skuId\":\"" + sku + "\",\"locationId\":\"" + loc + "\"}");
        assertThat(locData.get("expectedQty").asInt()).isEqualTo(12);
    }

    /** Start an instance in WAREHOUSE, post the lookup checkpoint with the given data, return the data. */
    private JsonNode runLookup(String stepData) throws Exception {
        String start = mvc.perform(post("/api/process-designer/instances")
                        .header("X-Auth-Roles", OPERATOR).header("X-Auth-User", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processKey\":\"" + KEY + "\",\"warehouseId\":\"" + WAREHOUSE + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String instanceId = mapper.readTree(start).get("instanceId").asText();

        String cp = mvc.perform(post("/api/process-designer/instances/" + instanceId + "/checkpoint")
                        .header("X-Auth-Roles", OPERATOR).header("X-Auth-User", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stepId\":\"lookup\",\"data\":" + stepData + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(cp).get("data");
    }

    private void publishLookupFlow() throws Exception {
        if (mvc.perform(get("/api/process-designer/defs/" + KEY + "/active").header("X-Auth-Roles", EDITOR))
                .andReturn().getResponse().getStatus() == 200) {
            return;
        }
        mvc.perform(post("/api/process-designer/defs")
                        .header("X-Auth-Roles", EDITOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processKey\":\"" + KEY + "\",\"title\":\"Lookup\"}"))
                .andExpect(status().isCreated());
        String def = """
            {
              "processKey": "lookup-flow",
              "title": "Lookup",
              "dataSchema": [
                { "name": "skuId", "type": "string" },
                { "name": "locationId", "type": "string" },
                { "name": "huId", "type": "string" },
                { "name": "expectedQty", "type": "number" }
              ],
              "start": "lookup",
              "steps": {
                "lookup": { "type": "task", "task": "inventory.lookup",
                            "input": { "skuId": "skuId", "locationId": "locationId", "huId": "huId" },
                            "output": { "expectedQty": "onHand" },
                            "next": "done" },
                "done": { "type": "screen", "screen": "acknowledge", "config": {} }
              }
            }
            """;
        mvc.perform(put("/api/process-designer/defs/" + KEY + "/1")
                        .header("X-Auth-Roles", EDITOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(def))
                .andExpect(status().isOk());
        mvc.perform(post("/api/process-designer/defs/" + KEY + "/1/publish")
                        .header("X-Auth-Roles", EDITOR).header("X-Auth-User", "alice"))
                .andExpect(status().isOk());
    }
}
