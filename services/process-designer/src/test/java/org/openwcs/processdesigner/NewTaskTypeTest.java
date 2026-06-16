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
 * A real new curated task type ({@code counting.capture}) executes through a task-step checkpoint:
 * the engine resolves its inputs from the data object, calls the counting station-count endpoint
 * (here mocked), forwards the operator's identity header, and merges the response outputs back.
 *
 * <p>The downstream HTTP is mocked by binding every autoconfigured {@code RestClient.Builder} (each
 * curated task builds its own) to a {@link MockRestServiceServer} via
 * {@link MockServerRestClientCustomizer}. The identity-forwarding customizer still applies, so the
 * operator's {@code X-Auth-*} ride along. The same expectation is registered on every bound server;
 * only the counting task's client fires it.
 */
@Import(NewTaskTypeTest.MockHttpConfig.class)
class NewTaskTypeTest extends AbstractIntegrationTest {

    private static final String EDITOR = "SUPERVISOR";
    private static final String OPERATOR = "OPERATOR";
    private static final String KEY = "count-flow";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    MockServerRestClientCustomizer mockServerCustomizer;

    @TestConfiguration
    static class MockHttpConfig {
        @Bean
        MockServerRestClientCustomizer mockServerRestClientCustomizer() {
            return new MockServerRestClientCustomizer();
        }
    }

    @Test
    void countingCaptureTaskExecutesForwardsIdentityAndMergesOutputs() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();

        // Publish a flow whose single task step runs counting.capture.
        publishCountFlow();

        // Stub the counting station-count call on every bound server (only the counting task's
        // client actually fires it); assert the path, identity header, and body. ExpectedCount.manyTimes
        // tolerates the servers that never receive a request.
        mockServerCustomizer.getServers().values().forEach(s ->
                s.expect(ExpectedCount.manyTimes(), requestTo(
                                "http://localhost:8095/api/counting/tasks/" + taskId + "/lines/" + lineId + "/station-count"))
                        .andExpect(method(HttpMethod.POST))
                        .andExpect(header("X-Auth-User", "carol"))
                        .andExpect(jsonPath("$.countedQty").value(7))
                        .andRespond(withSuccess(
                                "{\"outcome\":\"ACCEPTED\",\"message\":\"Count matches the system quantity.\"}",
                                MediaType.APPLICATION_JSON)));

        // Start an instance and post the task-step checkpoint with the resolved data.
        String startResponse = mvc.perform(post("/api/process-designer/instances")
                        .header("X-Auth-Roles", OPERATOR)
                        .header("X-Auth-User", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processKey\":\"" + KEY + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String instanceId = mapper.readTree(startResponse).get("instanceId").asText();

        String body = "{\"stepId\":\"capture\",\"data\":{"
                + "\"taskId\":\"" + taskId + "\",\"lineId\":\"" + lineId + "\",\"countedQty\":7}}";
        String cp = mvc.perform(post("/api/process-designer/instances/" + instanceId + "/checkpoint")
                        .header("X-Auth-Roles", OPERATOR)
                        .header("X-Auth-User", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode result = mapper.readTree(cp);
        assertThat(result.get("data").get("countOutcome").asText()).isEqualTo("ACCEPTED");
        assertThat(result.get("currentStep").asText()).isEqualTo("done");
    }

    private void publishCountFlow() throws Exception {
        if (mvc.perform(get("/api/process-designer/defs/" + KEY + "/active").header("X-Auth-Roles", EDITOR))
                .andReturn().getResponse().getStatus() == 200) {
            return;
        }
        mvc.perform(post("/api/process-designer/defs")
                        .header("X-Auth-Roles", EDITOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processKey\":\"" + KEY + "\",\"title\":\"Count\"}"))
                .andExpect(status().isCreated());
        String def = """
            {
              "processKey": "count-flow",
              "title": "Count",
              "dataSchema": [
                { "name": "taskId", "type": "string" },
                { "name": "lineId", "type": "string" },
                { "name": "countedQty", "type": "number" },
                { "name": "countOutcome", "type": "string" }
              ],
              "start": "capture",
              "steps": {
                "capture": { "type": "task", "task": "counting.capture",
                             "input": { "taskId": "taskId", "lineId": "lineId", "countedQty": "countedQty" },
                             "output": { "countOutcome": "outcome" },
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
