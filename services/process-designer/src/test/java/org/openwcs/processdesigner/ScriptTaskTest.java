package org.openwcs.processdesigner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * End-to-end coverage of the sandboxed script task (spec §7.2) through the real checkpoint path with
 * scripting enabled and the ADMIN role (which holds PROCESS_SCRIPT_AUTHOR):
 * <ul>
 *   <li>a script step computes a derived value from {@code data} and the declared output merges back;</li>
 *   <li>a snippet attempting host/Java access fails cleanly (no escape, service stays up);</li>
 *   <li>an infinite loop is stopped by the timeout/statement limit (no hang/crash).</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "openwcs.security.enabled=true",
        "openwcs.process-designer.scripting.enabled=true",
        // Keep the watchdog snappy for the infinite-loop test.
        "openwcs.process-designer.scripting.timeout-ms=1500"
})
@AutoConfigureMockMvc
class ScriptTaskTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final String ADMIN = "ADMIN";
    private static final String OPERATOR = "OPERATOR";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Test
    void scriptTaskComputesDerivedValueAndMergesOutput() throws Exception {
        String key = "script-derive";
        String script = "return { total: data.qty * data.price };";
        publishScriptFlow(key, script);

        String start = mvc.perform(post("/api/process-designer/instances")
                        .header("X-Auth-Roles", OPERATOR).header("X-Auth-User", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processKey\":\"" + key + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String instanceId = mapper.readTree(start).get("instanceId").asText();

        String cp = mvc.perform(post("/api/process-designer/instances/" + instanceId + "/checkpoint")
                        .header("X-Auth-Roles", OPERATOR).header("X-Auth-User", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stepId\":\"calc\",\"data\":{\"qty\":4,\"price\":3}}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode result = mapper.readTree(cp);
        assertThat(result.get("data").get("total").asInt()).isEqualTo(12);
        assertThat(result.get("currentStep").asText()).isEqualTo("done");
    }

    @Test
    void sandboxDeniesHostAccessCleanly() throws Exception {
        String key = "script-escape";
        // Attempt a sandbox escape: Java.type is not defined in the locked-down context.
        String script = "var f = Java.type('java.lang.System'); return { pwned: '' + f };";
        publishScriptFlow(key, script);

        String start = mvc.perform(post("/api/process-designer/instances")
                        .header("X-Auth-Roles", OPERATOR).header("X-Auth-User", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processKey\":\"" + key + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String instanceId = mapper.readTree(start).get("instanceId").asText();

        // The escape attempt fails cleanly with 502 (task failed); the service stays up.
        mvc.perform(post("/api/process-designer/instances/" + instanceId + "/checkpoint")
                        .header("X-Auth-Roles", OPERATOR).header("X-Auth-User", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stepId\":\"calc\",\"data\":{}}"))
                .andExpect(status().isBadGateway());

        // Service still up: a subsequent read works.
        mvc.perform(get("/api/process-designer/defs").header("X-Auth-Roles", ADMIN))
                .andExpect(status().isOk());
    }

    @Test
    void infiniteLoopIsStoppedAndServiceStaysUp() throws Exception {
        String key = "script-loop";
        String script = "while (true) {}";
        publishScriptFlow(key, script);

        String start = mvc.perform(post("/api/process-designer/instances")
                        .header("X-Auth-Roles", OPERATOR).header("X-Auth-User", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processKey\":\"" + key + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String instanceId = mapper.readTree(start).get("instanceId").asText();

        // Timeout/statement-limit cuts it off; 502 task failure, no hang.
        mvc.perform(post("/api/process-designer/instances/" + instanceId + "/checkpoint")
                        .header("X-Auth-Roles", OPERATOR).header("X-Auth-User", "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stepId\":\"calc\",\"data\":{}}"))
                .andExpect(status().isBadGateway());

        mvc.perform(get("/api/process-designer/defs").header("X-Auth-Roles", ADMIN))
                .andExpect(status().isOk());
    }

    private void publishScriptFlow(String key, String script) throws Exception {
        mvc.perform(post("/api/process-designer/defs")
                        .header("X-Auth-Roles", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processKey\":\"" + key + "\",\"title\":\"Script\"}"))
                .andExpect(status().isCreated());

        var def = mapper.createObjectNode();
        def.put("processKey", key);
        def.put("title", "Script");
        var schema = mapper.createArrayNode();
        schema.add(mapper.createObjectNode().put("name", "qty").put("type", "number"));
        schema.add(mapper.createObjectNode().put("name", "price").put("type", "number"));
        schema.add(mapper.createObjectNode().put("name", "total").put("type", "number"));
        def.set("dataSchema", schema);
        def.put("start", "calc");
        var steps = mapper.createObjectNode();
        var calc = mapper.createObjectNode();
        calc.put("type", "task");
        calc.put("task", "script");
        var config = mapper.createObjectNode();
        config.put("script", script);
        var outputs = mapper.createArrayNode();
        outputs.add(mapper.createObjectNode().put("name", "total"));
        config.set("outputs", outputs);
        calc.set("config", config);
        calc.put("next", "done");
        steps.set("calc", calc);
        var done = mapper.createObjectNode();
        done.put("type", "screen");
        done.put("screen", "acknowledge");
        done.set("config", mapper.createObjectNode());
        steps.set("done", done);
        def.set("steps", steps);

        mvc.perform(put("/api/process-designer/defs/" + key + "/1")
                        .header("X-Auth-Roles", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(def)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/process-designer/defs/" + key + "/1/publish")
                        .header("X-Auth-Roles", ADMIN).header("X-Auth-User", "alice"))
                .andExpect(status().isOk());
    }
}
