package org.openwcs.processdesigner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
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
 * The three fulfilment curated tasks ({@code picking.book}, {@code inventory.reduce},
 * {@code order.load}) execute through a task-step checkpoint: the engine auto-injects the running
 * instance's {@code warehouseId} / {@code instanceId} / operator, posts the right body to the
 * downstream service (mocked), and merges the outputs back. Also asserts the checkpoint appends a
 * continuous step-history event for replay. Downstream HTTP is mocked on every bound RestClient.
 */
@Import(FulfilmentTasksTest.MockHttpConfig.class)
class FulfilmentTasksTest extends AbstractIntegrationTest {

    private static final String EDITOR = "SUPERVISOR";
    private static final String OPERATOR = "OPERATOR";
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

    @BeforeEach
    void resetServers() {
        mockServerCustomizer.getServers().values().forEach(s -> s.reset());
    }

    @Test
    void pickingBookPostsConfirmWithProcessInstanceIdAndMapsOutputs() throws Exception {
        UUID line = UUID.randomUUID();
        UUID loc = UUID.randomUUID();
        String key = "book-flow";
        publishSingleTaskFlow(key, "picking.book",
                "{ \"lineId\": \"lineId\", \"locationId\": \"locationId\", \"pickedQty\": \"pickedQty\" }",
                "{ \"bookedQty\": \"bookedQty\", \"requestedQty\": \"requestedQty\", \"pickStatus\": \"pickStatus\" }",
                "[ { \"name\": \"lineId\", \"type\": \"string\" },"
                        + " { \"name\": \"locationId\", \"type\": \"string\" },"
                        + " { \"name\": \"pickedQty\", \"type\": \"number\" },"
                        + " { \"name\": \"bookedQty\", \"type\": \"number\" },"
                        + " { \"name\": \"requestedQty\", \"type\": \"number\" },"
                        + " { \"name\": \"pickStatus\", \"type\": \"string\" } ]");

        // order-management clamps to available: requested 7, booked 5, line goes SHORT.
        String response = "{\"lineId\":\"" + line + "\",\"pickedQty\":5,\"status\":\"SHORT\"}";
        mockServerCustomizer.getServers().values().forEach(s ->
                s.expect(ExpectedCount.manyTimes(),
                                requestTo("http://localhost:8084/api/orders/pick-tasks/" + line + "/confirm"))
                        .andExpect(method(HttpMethod.POST))
                        .andExpect(header("X-Auth-User", "carol"))
                        .andExpect(jsonPath("$.pickedQty").value(7))
                        .andExpect(jsonPath("$.locationId").value(loc.toString()))
                        .andExpect(jsonPath("$.processInstanceId").exists())
                        .andRespond(withSuccess(response, MediaType.APPLICATION_JSON)));

        JsonNode data = runSingleTask(key,
                "{\"lineId\":\"" + line + "\",\"locationId\":\"" + loc + "\",\"pickedQty\":7}");
        assertThat(data.get("bookedQty").asInt()).isEqualTo(5);
        assertThat(data.get("requestedQty").asInt()).isEqualTo(7);
        assertThat(data.get("pickStatus").asText()).isEqualTo("SHORT");
    }

    @Test
    void inventoryReducePostsNegativeStockAdjustedWithReason() throws Exception {
        UUID sku = UUID.randomUUID();
        UUID loc = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String key = "reduce-flow";
        publishSingleTaskFlow(key, "inventory.reduce",
                "{ \"skuId\": \"skuId\", \"locationId\": \"locationId\", \"qty\": \"qty\", \"reason\": \"reason\" }",
                "{ \"eventId\": \"eventId\" }",
                "[ { \"name\": \"skuId\", \"type\": \"string\" },"
                        + " { \"name\": \"locationId\", \"type\": \"string\" },"
                        + " { \"name\": \"qty\", \"type\": \"number\" },"
                        + " { \"name\": \"reason\", \"type\": \"string\" },"
                        + " { \"name\": \"eventId\", \"type\": \"string\" } ]");

        mockServerCustomizer.getServers().values().forEach(s -> {
            // Availability pre-check: on-hand 10 (>= requested 3) so the reduction is allowed.
            s.expect(ExpectedCount.manyTimes(), requestTo(
                            "http://localhost:8082/api/inventory/availability?warehouseId=" + WAREHOUSE
                                    + "&skuId=" + sku + "&locationId=" + loc))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess("{\"onHand\":10,\"available\":10}", MediaType.APPLICATION_JSON));
            // The negative StockAdjusted carries the reason and the forwarded operator.
            s.expect(ExpectedCount.manyTimes(), requestTo("http://localhost:8086/api/txlog/events"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(header("X-Auth-User", "carol"))
                    .andExpect(jsonPath("$.eventType").value("StockAdjusted"))
                    .andExpect(jsonPath("$.actor").value("carol"))
                    .andExpect(jsonPath("$.streamId").value(sku.toString()))
                    .andExpect(jsonPath("$.payload.qtyDelta").value(-3))
                    .andExpect(jsonPath("$.payload.reason").value("DAMAGED"))
                    .andExpect(jsonPath("$.payload.locationId").value(loc.toString()))
                    .andExpect(jsonPath("$.payload.processInstanceId").exists())
                    .andRespond(withSuccess("{\"eventId\":\"" + eventId + "\"}", MediaType.APPLICATION_JSON));
        });

        JsonNode data = runSingleTask(key,
                "{\"skuId\":\"" + sku + "\",\"locationId\":\"" + loc + "\",\"qty\":3,\"reason\":\"DAMAGED\"}");
        assertThat(data.get("eventId").asText()).isEqualTo(eventId.toString());
    }

    @Test
    void inventoryReduceRejectsOverReduction() throws Exception {
        UUID sku = UUID.randomUUID();
        UUID loc = UUID.randomUUID();
        String key = "reduce-reject-flow";
        publishSingleTaskFlow(key, "inventory.reduce",
                "{ \"skuId\": \"skuId\", \"locationId\": \"locationId\", \"qty\": \"qty\", \"reason\": \"reason\" }",
                "{ \"eventId\": \"eventId\" }",
                "[ { \"name\": \"skuId\", \"type\": \"string\" },"
                        + " { \"name\": \"locationId\", \"type\": \"string\" },"
                        + " { \"name\": \"qty\", \"type\": \"number\" },"
                        + " { \"name\": \"reason\", \"type\": \"string\" },"
                        + " { \"name\": \"eventId\", \"type\": \"string\" } ]");

        // On-hand 2 but the operator tries to remove 5 -> rejected (502), no txlog event posted.
        mockServerCustomizer.getServers().values().forEach(s ->
                s.expect(ExpectedCount.manyTimes(), requestTo(
                                "http://localhost:8082/api/inventory/availability?warehouseId=" + WAREHOUSE
                                        + "&skuId=" + sku + "&locationId=" + loc))
                        .andExpect(method(HttpMethod.GET))
                        .andRespond(withSuccess("{\"onHand\":2,\"available\":2}", MediaType.APPLICATION_JSON)));

        String instanceId = startInstance(key);
        mvc.perform(post("/api/process-designer/instances/" + instanceId + "/checkpoint")
                        .header("X-Auth-Roles", OPERATOR).header("X-Auth-User", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stepId\":\"run\",\"data\":{\"skuId\":\"" + sku + "\",\"locationId\":\""
                                + loc + "\",\"qty\":5,\"reason\":\"DAMAGED\"}}"))
                .andExpect(status().isBadGateway());
    }

    @Test
    void orderLoadResolvesByCode() throws Exception {
        UUID orderId = UUID.randomUUID();
        String key = "load-flow";
        publishSingleTaskFlow(key, "order.load",
                "{ \"orderCode\": \"orderCode\" }",
                "{ \"found\": \"found\", \"orderId\": \"orderId\", \"orderRef\": \"orderRef\","
                        + " \"orderType\": \"orderType\", \"orderStatus\": \"orderStatus\","
                        + " \"lineCount\": \"lineCount\" }",
                "[ { \"name\": \"orderCode\", \"type\": \"string\" },"
                        + " { \"name\": \"found\", \"type\": \"boolean\" },"
                        + " { \"name\": \"orderId\", \"type\": \"string\" },"
                        + " { \"name\": \"orderRef\", \"type\": \"string\" },"
                        + " { \"name\": \"orderType\", \"type\": \"string\" },"
                        + " { \"name\": \"orderStatus\", \"type\": \"string\" },"
                        + " { \"name\": \"lineCount\", \"type\": \"number\" } ]");

        String response = "{\"found\":true,\"order\":{\"orderId\":\"" + orderId + "\",\"orderRef\":\"SO-9\","
                + "\"orderType\":\"OUTBOUND\",\"status\":\"RELEASED\",\"customerRef\":\"ACME\",\"lineCount\":4}}";
        mockServerCustomizer.getServers().values().forEach(s ->
                s.expect(ExpectedCount.manyTimes(), requestTo(
                                "http://localhost:8084/api/orders/resolve?warehouseId=" + WAREHOUSE + "&ref=SO-9"))
                        .andExpect(method(HttpMethod.GET))
                        .andExpect(header("X-Auth-User", "carol"))
                        .andRespond(withSuccess(response, MediaType.APPLICATION_JSON)));

        JsonNode data = runSingleTask(key, "{\"orderCode\":\"SO-9\"}");
        assertThat(data.get("found").asBoolean()).isTrue();
        assertThat(data.get("orderId").asText()).isEqualTo(orderId.toString());
        assertThat(data.get("orderRef").asText()).isEqualTo("SO-9");
        assertThat(data.get("orderType").asText()).isEqualTo("OUTBOUND");
        assertThat(data.get("orderStatus").asText()).isEqualTo("RELEASED");
        assertThat(data.get("lineCount").asInt()).isEqualTo(4);
    }

    @Test
    void checkpointAppendsContinuousStepHistoryEvent() throws Exception {
        UUID orderId = UUID.randomUUID();
        String key = "load-history-flow";
        publishSingleTaskFlow(key, "order.load",
                "{ \"orderCode\": \"orderCode\" }",
                "{ \"found\": \"found\" }",
                "[ { \"name\": \"orderCode\", \"type\": \"string\" },"
                        + " { \"name\": \"found\", \"type\": \"boolean\" } ]");
        String response = "{\"found\":true,\"order\":{\"orderId\":\"" + orderId + "\",\"orderRef\":\"SO-1\","
                + "\"orderType\":\"OUTBOUND\",\"status\":\"RELEASED\",\"lineCount\":1}}";
        mockServerCustomizer.getServers().values().forEach(s ->
                s.expect(ExpectedCount.manyTimes(), requestTo(
                                "http://localhost:8084/api/orders/resolve?warehouseId=" + WAREHOUSE + "&ref=SO-1"))
                        .andExpect(method(HttpMethod.GET))
                        .andRespond(withSuccess(response, MediaType.APPLICATION_JSON)));

        String instanceId = startInstance(key);
        // A screen advance first (seq 1), then the task checkpoint (server-derived seq 2).
        mvc.perform(post("/api/process-designer/instances/" + instanceId + "/step")
                        .header("X-Auth-Roles", OPERATOR).header("X-Auth-User", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seq\":1,\"stepId\":\"run\",\"stepType\":\"screen\",\"data\":{\"orderCode\":\"SO-1\"}}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/process-designer/instances/" + instanceId + "/checkpoint")
                        .header("X-Auth-Roles", OPERATOR).header("X-Auth-User", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stepId\":\"run\",\"data\":{\"orderCode\":\"SO-1\"}}"))
                .andExpect(status().isOk());

        String steps = mvc.perform(get("/api/process-designer/instances/" + instanceId + "/steps")
                        .header("X-Auth-Roles", OPERATOR))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode events = mapper.readTree(steps);
        // Two ordered events: the screen advance then the task checkpoint (step_type from the def step).
        assertThat(events.size()).isEqualTo(2);
        assertThat(events.get(0).get("seq").asLong()).isEqualTo(1L);
        assertThat(events.get(0).get("stepType").asText()).isEqualTo("screen");
        assertThat(events.get(1).get("seq").asLong()).isEqualTo(2L);
        assertThat(events.get(1).get("stepType").asText()).isEqualTo("task");
        assertThat(events.get(1).get("stepId").asText()).isEqualTo("run");
    }

    // --- helpers --------------------------------------------------------------------------------

    private JsonNode runSingleTask(String key, String stepData) throws Exception {
        String instanceId = startInstance(key);
        String cp = mvc.perform(post("/api/process-designer/instances/" + instanceId + "/checkpoint")
                        .header("X-Auth-Roles", OPERATOR).header("X-Auth-User", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stepId\":\"run\",\"data\":" + stepData + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(cp).get("data");
    }

    private String startInstance(String key) throws Exception {
        String start = mvc.perform(post("/api/process-designer/instances")
                        .header("X-Auth-Roles", OPERATOR).header("X-Auth-User", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processKey\":\"" + key + "\",\"warehouseId\":\"" + WAREHOUSE + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(start).get("instanceId").asText();
    }

    private void publishSingleTaskFlow(String key, String taskType, String inputJson, String outputJson,
                                       String dataSchemaJson) throws Exception {
        if (mvc.perform(get("/api/process-designer/defs/" + key + "/active").header("X-Auth-Roles", EDITOR))
                .andReturn().getResponse().getStatus() == 200) {
            return;
        }
        mvc.perform(post("/api/process-designer/defs")
                        .header("X-Auth-Roles", EDITOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processKey\":\"" + key + "\",\"title\":\"Flow\"}"))
                .andExpect(status().isCreated());
        String def = "{\n"
                + "  \"processKey\": \"" + key + "\",\n"
                + "  \"title\": \"Flow\",\n"
                + "  \"dataSchema\": " + dataSchemaJson + ",\n"
                + "  \"start\": \"run\",\n"
                + "  \"steps\": {\n"
                + "    \"run\": { \"type\": \"task\", \"task\": \"" + taskType + "\",\n"
                + "      \"input\": " + inputJson + ",\n"
                + "      \"output\": " + outputJson + ",\n"
                + "      \"next\": \"done\" },\n"
                + "    \"done\": { \"type\": \"screen\", \"screen\": \"acknowledge\", \"config\": {} }\n"
                + "  }\n"
                + "}";
        mvc.perform(put("/api/process-designer/defs/" + key + "/1")
                        .header("X-Auth-Roles", EDITOR)
                        .contentType(MediaType.APPLICATION_JSON).content(def))
                .andExpect(status().isOk());
        mvc.perform(post("/api/process-designer/defs/" + key + "/1/publish")
                        .header("X-Auth-Roles", EDITOR).header("X-Auth-User", "alice"))
                .andExpect(status().isOk());
    }
}
