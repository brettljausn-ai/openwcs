package org.openwcs.processdesigner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Instance start returns the active def + start step; a checkpoint runs the curated task (with the
 * operator's identity in scope) and merges outputs and advances; re-posting the same checkpoint is
 * idempotent (the task is not re-run); and resume returns the state.
 */
@Import(RecordingTaskConfig.class)
class InstanceCheckpointTest extends AbstractIntegrationTest {

    private static final String EDITOR = "SUPERVISOR";
    private static final String OPERATOR = "OPERATOR";
    private static final String KEY = "echo-flow";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    RecordingTaskConfig.EchoTask echoTask;

    /** Publish an echo-flow: one screen -> one task (test.echo) -> done. */
    @BeforeEach
    void publishEchoFlow() throws Exception {
        // Only publish once across tests (ACTIVE already exists -> create fresh key per run is simpler).
        if (echoFlowActive()) {
            return;
        }
        mvc.perform(post("/api/process-designer/defs")
                        .header("X-Auth-Roles", EDITOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processKey\":\"" + KEY + "\",\"title\":\"Echo\"}"))
                .andExpect(status().isCreated());

        String body = """
            {
              "processKey": "echo-flow",
              "title": "Echo",
              "dataSchema": [ { "name": "thing", "type": "string" }, { "name": "echoed", "type": "string" } ],
              "start": "enter",
              "steps": {
                "enter": { "type": "screen", "screen": "textInput",
                           "config": { "writeTo": "thing", "validation": { "required": true } },
                           "next": "run" },
                "run": { "type": "task", "task": "test.echo",
                         "input": { "in": "thing" },
                         "output": { "echoed": "echoed" },
                         "next": "done" },
                "done": { "type": "screen", "screen": "acknowledge", "config": {} }
              }
            }
            """;
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/process-designer/defs/" + KEY + "/1")
                        .header("X-Auth-Roles", EDITOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
        mvc.perform(post("/api/process-designer/defs/" + KEY + "/1/publish")
                        .header("X-Auth-Roles", EDITOR).header("X-Auth-User", "alice"))
                .andExpect(status().isOk());
    }

    private boolean echoFlowActive() throws Exception {
        return mvc.perform(get("/api/process-designer/defs/" + KEY + "/active").header("X-Auth-Roles", EDITOR))
                .andReturn().getResponse().getStatus() == 200;
    }

    @Test
    void startCheckpointMergeAdvanceResumeAndIdempotent() throws Exception {
        // Start: returns active def + start step.
        String startResponse = mvc.perform(post("/api/process-designer/instances")
                        .header("X-Auth-Roles", OPERATOR)
                        .header("X-Auth-User", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processKey\":\"" + KEY + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currentStep").value("enter"))
                .andExpect(jsonPath("$.def.steps.run.task").value("test.echo"))
                .andReturn().getResponse().getContentAsString();
        JsonNode start = mapper.readTree(startResponse);
        String instanceId = start.get("instanceId").asText();

        int before = echoTask.invocations.get();

        // Checkpoint the task step, posting the captured screen data.
        String cpResponse = mvc.perform(post("/api/process-designer/instances/" + instanceId + "/checkpoint")
                        .header("X-Auth-Roles", OPERATOR)
                        .header("X-Auth-User", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stepId\":\"run\",\"data\":{\"thing\":\"widget\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStep").value("done"))
                .andExpect(jsonPath("$.data.echoed").value("widget"))
                .andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(cpResponse).get("done").asBoolean()).isFalse();

        // Identity was forwarded into the task's request scope, and inputs resolved from the data object.
        assertThat(echoTask.invocations.get()).isEqualTo(before + 1);
        assertThat(echoTask.lastAuthUser).isEqualTo("carol");
        assertThat(echoTask.lastInputs.get("in")).isEqualTo("widget");

        // Idempotent re-post: same result, task NOT re-run.
        mvc.perform(post("/api/process-designer/instances/" + instanceId + "/checkpoint")
                        .header("X-Auth-Roles", OPERATOR)
                        .header("X-Auth-User", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stepId\":\"run\",\"data\":{\"thing\":\"changed\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStep").value("done"))
                .andExpect(jsonPath("$.data.echoed").value("widget"));
        assertThat(echoTask.invocations.get()).isEqualTo(before + 1);

        // Resume returns the persisted state.
        mvc.perform(get("/api/process-designer/instances/" + instanceId).header("X-Auth-Roles", OPERATOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStep").value("done"))
                .andExpect(jsonPath("$.data.thing").value("widget"))
                .andExpect(jsonPath("$.data.echoed").value("widget"));
    }

    @Test
    void checkpointOnScreenStepIsRejected() throws Exception {
        String startResponse = mvc.perform(post("/api/process-designer/instances")
                        .header("X-Auth-Roles", OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processKey\":\"" + KEY + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String instanceId = mapper.readTree(startResponse).get("instanceId").asText();

        mvc.perform(post("/api/process-designer/instances/" + instanceId + "/checkpoint")
                        .header("X-Auth-Roles", OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stepId\":\"enter\",\"data\":{}}"))
                .andExpect(status().isBadRequest());
    }
}
