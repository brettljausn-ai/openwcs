package org.openwcs.processdesigner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Per-screen step history + assignment (the "Fulfilment integrity + RF resilience" epic):
 * <ul>
 *   <li>start sets assignedTo = startedBy;</li>
 *   <li>the step endpoint appends ordered events, is idempotent on (instanceId, seq), and makes the
 *       server current_step exact;</li>
 *   <li>GET /steps returns the ordered replay rows (screen advances + the task checkpoint);</li>
 *   <li>reassign sets assignedTo, keeps startedBy, writes an audit event, and is supervisor-gated;</li>
 *   <li>the assignedTo filter on the list selects an operator's own work.</li>
 * </ul>
 */
class InstanceStepHistoryTest extends AbstractIntegrationTest {

    private static final String EDITOR = "SUPERVISOR";
    private static final String OPERATOR = "OPERATOR";
    private static final String KEY = "history-flow";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    /** A simple two-screen flow (no task step needed to exercise the step endpoint). */
    @BeforeEach
    void publishFlow() throws Exception {
        if (mvc.perform(get("/api/process-designer/defs/" + KEY + "/active").header("X-Auth-Roles", EDITOR))
                .andReturn().getResponse().getStatus() == 200) {
            return;
        }
        mvc.perform(post("/api/process-designer/defs")
                        .header("X-Auth-Roles", EDITOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processKey\":\"" + KEY + "\",\"title\":\"History\"}"))
                .andExpect(status().isCreated());
        String def = """
            {
              "processKey": "history-flow",
              "title": "History",
              "dataSchema": [ { "name": "a", "type": "string" }, { "name": "b", "type": "string" } ],
              "start": "one",
              "steps": {
                "one": { "type": "screen", "screen": "textInput", "config": { "writeTo": "a" }, "next": "two" },
                "two": { "type": "screen", "screen": "acknowledge", "config": {} }
              }
            }
            """;
        mvc.perform(put("/api/process-designer/defs/" + KEY + "/1")
                        .header("X-Auth-Roles", EDITOR)
                        .contentType(MediaType.APPLICATION_JSON).content(def))
                .andExpect(status().isOk());
        mvc.perform(post("/api/process-designer/defs/" + KEY + "/1/publish")
                        .header("X-Auth-Roles", EDITOR).header("X-Auth-User", "alice"))
                .andExpect(status().isOk());
    }

    @Test
    void startSetsAssignedToStartedBy() throws Exception {
        mvc.perform(post("/api/process-designer/instances")
                        .header("X-Auth-Roles", OPERATOR).header("X-Auth-User", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processKey\":\"" + KEY + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assignedTo").value("carol"));
    }

    @Test
    void stepEndpointAppendsOrderedEventsIdempotentAndExactCurrentStep() throws Exception {
        String instanceId = start("carol");

        // Advance to screen "two" (seq 1), merging data.
        mvc.perform(post("/api/process-designer/instances/" + instanceId + "/step")
                        .header("X-Auth-Roles", OPERATOR).header("X-Auth-User", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seq\":1,\"stepId\":\"two\",\"stepType\":\"screen\",\"data\":{\"a\":\"x\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStep").value("two"))
                .andExpect(jsonPath("$.data.a").value("x"));

        // Idempotent re-post of seq 1 with DIFFERENT data must not re-apply (data.a stays "x").
        mvc.perform(post("/api/process-designer/instances/" + instanceId + "/step")
                        .header("X-Auth-Roles", OPERATOR).header("X-Auth-User", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seq\":1,\"stepId\":\"two\",\"stepType\":\"screen\",\"data\":{\"a\":\"y\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.a").value("x"));

        // Resume sees the exact current step + data.
        mvc.perform(get("/api/process-designer/instances/" + instanceId).header("X-Auth-Roles", OPERATOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStep").value("two"))
                .andExpect(jsonPath("$.data.a").value("x"));

        // GET /steps returns exactly one ordered event (the single applied advance).
        String steps = mvc.perform(get("/api/process-designer/instances/" + instanceId + "/steps")
                        .header("X-Auth-Roles", OPERATOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].seq").value(1))
                .andExpect(jsonPath("$[0].stepId").value("two"))
                .andExpect(jsonPath("$[0].stepType").value("screen"))
                .andExpect(jsonPath("$[0].enteredBy").value("carol"))
                .andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(steps).size()).isEqualTo(1);
    }

    @Test
    void reassignSetsAssignedToKeepsStartedByAndWritesAuditEventGated() throws Exception {
        String instanceId = start("carol");

        // Operator (VIEW only) cannot reassign.
        mvc.perform(post("/api/process-designer/instances/" + instanceId + "/reassign")
                        .header("X-Auth-Roles", OPERATOR).header("X-Auth-User", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toUser\":\"dave\"}"))
                .andExpect(status().isForbidden());

        // Supervisor reassigns to dave.
        mvc.perform(post("/api/process-designer/instances/" + instanceId + "/reassign")
                        .header("X-Auth-Roles", EDITOR).header("X-Auth-User", "boss")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toUser\":\"dave\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedTo").value("dave"));

        // startedBy is unchanged in the monitoring summary; assignedTo moved to dave.
        String listed = mvc.perform(get("/api/process-designer/instances")
                        .param("processKey", KEY).param("assignedTo", "dave")
                        .header("X-Auth-Roles", EDITOR))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode arr = mapper.readTree(listed);
        assertThat(arr.size()).isGreaterThanOrEqualTo(1);
        boolean found = false;
        for (JsonNode n : arr) {
            if (n.get("instanceId").asText().equals(instanceId)) {
                found = true;
                assertThat(n.get("startedBy").asText()).isEqualTo("carol");
                assertThat(n.get("assignedTo").asText()).isEqualTo("dave");
            }
        }
        assertThat(found).isTrue();

        // The audit reassign event is in the step history.
        String steps = mvc.perform(get("/api/process-designer/instances/" + instanceId + "/steps")
                        .header("X-Auth-Roles", EDITOR))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode events = mapper.readTree(steps);
        JsonNode reassign = null;
        for (JsonNode e : events) {
            if ("reassign".equals(e.path("stepType").asText())) {
                reassign = e;
            }
        }
        assertThat(reassign).isNotNull();
        assertThat(reassign.get("enteredBy").asText()).isEqualTo("boss");
        assertThat(reassign.get("data").get("from").asText()).isEqualTo("carol");
        assertThat(reassign.get("data").get("to").asText()).isEqualTo("dave");
    }

    @Test
    void assignedToFilterSelectsOwnWork() throws Exception {
        String mine = start("erin");
        start("frank");

        String listed = mvc.perform(get("/api/process-designer/instances")
                        .param("processKey", KEY).param("assignedTo", "erin")
                        .header("X-Auth-Roles", OPERATOR))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode arr = mapper.readTree(listed);
        assertThat(arr.size()).isGreaterThanOrEqualTo(1);
        for (JsonNode n : arr) {
            assertThat(n.get("assignedTo").asText()).isEqualTo("erin");
        }
        java.util.List<String> ids = new java.util.ArrayList<>();
        arr.forEach(n -> ids.add(n.get("instanceId").asText()));
        assertThat(ids).contains(mine);
    }

    private String start(String user) throws Exception {
        String resp = mvc.perform(post("/api/process-designer/instances")
                        .header("X-Auth-Roles", OPERATOR).header("X-Auth-User", user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processKey\":\"" + KEY + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(resp).get("instanceId").asText();
    }
}
