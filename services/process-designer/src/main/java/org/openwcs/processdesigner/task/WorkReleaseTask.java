package org.openwcs.processdesigner.task;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Curated task {@code work.release}: free any stock-check lines and replenishment tasks the running
 * instance has claimed, so abandoning a process (or exiting an area with no more work) does not leave
 * reservations stranded. Calls BOTH {@code POST /api/counting/lines/release} and
 * {@code POST /api/slotting/replenishment/release} with the auto-injected {@code instanceId}. There
 * are no operator inputs: the instance id is reserved context. Each downstream call is best-effort:
 * a failure (or one service being down) is logged and swallowed so the other still runs, and a
 * release that frees nothing is fine. Safe to call more than once (the downstream releases are
 * idempotent on the instance id).
 */
@Component
public class WorkReleaseTask implements ProcessTask {

    private static final Logger log = LoggerFactory.getLogger(WorkReleaseTask.class);

    private final RestClient counting;
    private final RestClient slotting;

    public WorkReleaseTask(RestClient.Builder builder,
                           @Value("${openwcs.process-designer.counting-base-url:http://localhost:8095}") String countingBaseUrl,
                           @Value("${openwcs.process-designer.slotting-base-url:http://localhost:8093}") String slottingBaseUrl) {
        this.counting = builder.baseUrl(countingBaseUrl).build();
        this.slotting = builder.clone().baseUrl(slottingBaseUrl).build();
    }

    @Override
    public String type() {
        return "work.release";
    }

    @Override
    public TaskSpec spec() {
        // No operator inputs: the running instanceId is reserved context, injected by the engine.
        return new TaskSpec("work.release", "Release my reserved work",
                "Free any stock-check lines and replenishment tasks this process run has claimed "
                        + "(safe to call more than once; tolerates a service being down).",
                List.of(),
                List.of(TaskSpec.out("released", "Always true once the best-effort releases have run"),
                        TaskSpec.out("countingFreed", "How many stock-check lines were freed (0 if none / unavailable)"),
                        TaskSpec.out("slottingFreed", "How many replenishment tasks were freed (0 if none / unavailable)")));
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> in) {
        String instanceId = TaskInputs.requireString(in, "instanceId");
        Map<String, Object> body = new HashMap<>();
        body.put("instanceId", instanceId);

        Object countingFreed = release(counting, "/api/counting/lines/release", body, "counting");
        Object slottingFreed = release(slotting, "/api/slotting/replenishment/release", body, "slotting");

        Map<String, Object> out = new HashMap<>();
        out.put("released", true);
        out.put("countingFreed", countingFreed == null ? 0 : countingFreed);
        out.put("slottingFreed", slottingFreed == null ? 0 : slottingFreed);
        return out;
    }

    /** Best-effort release: returns the downstream {@code freed} count, or null when the call failed. */
    private Object release(RestClient http, String uri, Map<String, Object> body, String service) {
        try {
            Map<String, Object> response = http.post()
                    .uri(uri)
                    .body(body)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() { });
            return response == null ? null : response.get("freed");
        } catch (RestClientException e) {
            // One service being down must not fail the release of the other (or strand the process).
            log.warn("work.release: {} release call failed (ignored): {}", service, e.getMessage());
            return null;
        }
    }
}
