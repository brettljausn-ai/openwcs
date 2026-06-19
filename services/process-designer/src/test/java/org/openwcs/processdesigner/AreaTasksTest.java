package org.openwcs.processdesigner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
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
 * The Master Data Areas curated tasks ({@code stockcheck.next}, {@code replenishment.next},
 * {@code work.release}) execute through a task-step checkpoint: the engine auto-injects the running
 * instance's {@code warehouseId} and {@code instanceId}, posts the right body to the downstream
 * service (mocked), and merges the outputs back. {@code work.release} calls BOTH counting and
 * slotting and tolerates one being down. Downstream HTTP is mocked on every bound {@code RestClient}.
 */
@Import(AreaTasksTest.MockHttpConfig.class)
class AreaTasksTest extends AbstractIntegrationTest {

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
        // The bound MockRestServiceServers are shared across the cached context; reset so each test
        // starts with a clean expectation set (otherwise a prior test's request blocks new expects).
        mockServerCustomizer.getServers().values().forEach(s -> s.reset());
    }

    @Test
    void stockCheckNextPostsAreaWarehouseInstanceAndMapsOutputs() throws Exception {
        UUID area = UUID.randomUUID();
        UUID line = UUID.randomUUID();
        UUID loc = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        UUID countTask = UUID.randomUUID();
        String key = "sc-next-flow";
        publishSingleTaskFlow(key, "stockcheck.next",
                "{ \"areaId\": \"areaId\" }",
                "{ \"found\": \"found\", \"lineId\": \"lineId\", \"locationId\": \"locationId\","
                        + " \"skuId\": \"skuId\", \"expectedQty\": \"expectedQty\","
                        + " \"countTaskId\": \"countTaskId\", \"uom\": \"uomCode\" }",
                "[ { \"name\": \"areaId\", \"type\": \"string\" },"
                        + " { \"name\": \"found\", \"type\": \"boolean\" },"
                        + " { \"name\": \"lineId\", \"type\": \"string\" },"
                        + " { \"name\": \"locationId\", \"type\": \"string\" },"
                        + " { \"name\": \"skuId\", \"type\": \"string\" },"
                        + " { \"name\": \"expectedQty\", \"type\": \"number\" },"
                        + " { \"name\": \"countTaskId\", \"type\": \"string\" },"
                        + " { \"name\": \"uom\", \"type\": \"string\" } ]");

        String response = "{\"found\":true,\"countTaskId\":\"" + countTask + "\",\"lineId\":\"" + line
                + "\",\"locationId\":\"" + loc + "\",\"skuId\":\"" + sku
                + "\",\"expectedQty\":5,\"uomCode\":\"EA\",\"countType\":\"FULL\"}";
        // Assert the body carries the auto-injected warehouseId + instanceId (instanceId is the running id).
        String[] instanceIdHolder = new String[1];
        mockServerCustomizer.getServers().values().forEach(s ->
                s.expect(ExpectedCount.manyTimes(),
                                requestTo("http://localhost:8095/api/counting/lines/claim-next"))
                        .andExpect(method(HttpMethod.POST))
                        .andExpect(header("X-Auth-User", "carol"))
                        .andExpect(jsonPath("$.warehouseId").value(WAREHOUSE))
                        .andExpect(jsonPath("$.areaId").value(area.toString()))
                        .andExpect(jsonPath("$.instanceId").exists())
                        .andRespond(withSuccess(response, MediaType.APPLICATION_JSON)));

        JsonNode data = runSingleTask(key, "next",
                "{\"areaId\":\"" + area + "\"}", instanceIdHolder);
        assertThat(data.get("found").asBoolean()).isTrue();
        assertThat(data.get("lineId").asText()).isEqualTo(line.toString());
        assertThat(data.get("locationId").asText()).isEqualTo(loc.toString());
        assertThat(data.get("skuId").asText()).isEqualTo(sku.toString());
        assertThat(data.get("expectedQty").asInt()).isEqualTo(5);
        assertThat(data.get("countTaskId").asText()).isEqualTo(countTask.toString());
        assertThat(data.get("uom").asText()).isEqualTo("EA");
    }

    @Test
    void replenishmentNextPostsAreaWarehouseInstanceAndMapsOutputs() throws Exception {
        UUID area = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        String key = "rep-next-flow";
        publishSingleTaskFlow(key, "replenishment.next",
                "{ \"areaId\": \"areaId\" }",
                "{ \"found\": \"found\", \"taskId\": \"taskId\", \"skuId\": \"skuId\","
                        + " \"fromLocationId\": \"fromLocationId\", \"toLocationId\": \"toLocationId\","
                        + " \"qty\": \"qty\", \"uomId\": \"uomId\", \"priority\": \"priority\" }",
                "[ { \"name\": \"areaId\", \"type\": \"string\" },"
                        + " { \"name\": \"found\", \"type\": \"boolean\" },"
                        + " { \"name\": \"taskId\", \"type\": \"string\" },"
                        + " { \"name\": \"skuId\", \"type\": \"string\" },"
                        + " { \"name\": \"fromLocationId\", \"type\": \"string\" },"
                        + " { \"name\": \"toLocationId\", \"type\": \"string\" },"
                        + " { \"name\": \"qty\", \"type\": \"number\" },"
                        + " { \"name\": \"uomId\", \"type\": \"string\" },"
                        + " { \"name\": \"priority\", \"type\": \"number\" } ]");

        String response = "{\"found\":true,\"taskId\":\"" + taskId + "\",\"skuId\":\"" + sku
                + "\",\"fromLocationId\":\"" + from + "\",\"toLocationId\":\"" + to
                + "\",\"qty\":12,\"uomId\":\"u1\",\"priority\":3}";
        mockServerCustomizer.getServers().values().forEach(s ->
                s.expect(ExpectedCount.manyTimes(),
                                requestTo("http://localhost:8093/api/slotting/replenishment/claim-next"))
                        .andExpect(method(HttpMethod.POST))
                        .andExpect(header("X-Auth-User", "carol"))
                        .andExpect(jsonPath("$.warehouseId").value(WAREHOUSE))
                        .andExpect(jsonPath("$.areaId").value(area.toString()))
                        .andExpect(jsonPath("$.instanceId").exists())
                        .andRespond(withSuccess(response, MediaType.APPLICATION_JSON)));

        JsonNode data = runSingleTask(key, "next", "{\"areaId\":\"" + area + "\"}", new String[1]);
        assertThat(data.get("found").asBoolean()).isTrue();
        assertThat(data.get("taskId").asText()).isEqualTo(taskId.toString());
        assertThat(data.get("fromLocationId").asText()).isEqualTo(from.toString());
        assertThat(data.get("toLocationId").asText()).isEqualTo(to.toString());
        assertThat(data.get("qty").asInt()).isEqualTo(12);
        assertThat(data.get("uomId").asText()).isEqualTo("u1");
        assertThat(data.get("priority").asInt()).isEqualTo(3);
    }

    @Test
    void workReleaseCallsBothServicesAndToleratesOneFailing() throws Exception {
        String key = "release-flow";
        publishSingleTaskFlow(key, "work.release", "{}",
                "{ \"released\": \"released\", \"countingFreed\": \"countingFreed\","
                        + " \"slottingFreed\": \"slottingFreed\" }",
                "[ { \"name\": \"released\", \"type\": \"boolean\" },"
                        + " { \"name\": \"countingFreed\", \"type\": \"number\" },"
                        + " { \"name\": \"slottingFreed\", \"type\": \"number\" } ]");

        // counting release succeeds (freed 2); slotting release is down (500) -> swallowed, freed 0.
        mockServerCustomizer.getServers().values().forEach(s -> {
            s.expect(ExpectedCount.manyTimes(),
                            requestTo("http://localhost:8095/api/counting/lines/release"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(jsonPath("$.instanceId").exists())
                    .andRespond(withSuccess("{\"freed\":2}", MediaType.APPLICATION_JSON));
            s.expect(ExpectedCount.manyTimes(),
                            requestTo("http://localhost:8093/api/slotting/replenishment/release"))
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(withServerError());
        });

        JsonNode data = runSingleTask(key, "release", "{}", new String[1]);
        assertThat(data.get("released").asBoolean()).isTrue();
        assertThat(data.get("countingFreed").asInt()).isEqualTo(2);
        // Slotting was down: the release still completes, freeing 0 there.
        assertThat(data.get("slottingFreed").asInt()).isEqualTo(0);
    }

    // --- helpers --------------------------------------------------------------------------------

    private JsonNode runSingleTask(String key, String stepId, String stepData, String[] instanceIdHolder)
            throws Exception {
        String start = mvc.perform(post("/api/process-designer/instances")
                        .header("X-Auth-Roles", OPERATOR).header("X-Auth-User", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processKey\":\"" + key + "\",\"warehouseId\":\"" + WAREHOUSE + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String instanceId = mapper.readTree(start).get("instanceId").asText();
        instanceIdHolder[0] = instanceId;

        String cp = mvc.perform(post("/api/process-designer/instances/" + instanceId + "/checkpoint")
                        .header("X-Auth-Roles", OPERATOR).header("X-Auth-User", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stepId\":\"" + stepId + "\",\"data\":" + stepData + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(cp).get("data");
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
        String stepId = "work.release".equals(taskType) ? "release" : "next";
        String def = "{\n"
                + "  \"processKey\": \"" + key + "\",\n"
                + "  \"title\": \"Flow\",\n"
                + "  \"dataSchema\": " + dataSchemaJson + ",\n"
                + "  \"start\": \"" + stepId + "\",\n"
                + "  \"steps\": {\n"
                + "    \"" + stepId + "\": { \"type\": \"task\", \"task\": \"" + taskType + "\",\n"
                + "      \"input\": " + inputJson + ",\n"
                + "      \"output\": " + outputJson + ",\n"
                + "      \"next\": \"done\" },\n"
                + "    \"done\": { \"type\": \"screen\", \"screen\": \"acknowledge\", \"config\": {} }\n"
                + "  }\n"
                + "}";
        mvc.perform(put("/api/process-designer/defs/" + key + "/1")
                        .header("X-Auth-Roles", EDITOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(def))
                .andExpect(status().isOk());
        mvc.perform(post("/api/process-designer/defs/" + key + "/1/publish")
                        .header("X-Auth-Roles", EDITOR).header("X-Auth-User", "alice"))
                .andExpect(status().isOk());
    }
}
