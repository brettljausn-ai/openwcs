package org.openwcs.processdesigner.task;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Curated task {@code counting.capture}: record one at-station blind count for a count-task line,
 * calling {@code POST /api/counting/tasks/{taskId}/lines/{lineId}/station-count} on counting (see
 * {@code StationCountRequest}). Reads {@code taskId}, {@code lineId} (required, path) and
 * {@code countedQty} (required, body). Returns the count {@code outcome}
 * (ACCEPTED | RECOUNT | ADJUSTED) and a human-readable {@code message} so the flow can branch on the
 * outcome (e.g. loop back on RECOUNT).
 */
@Component
public class CountingCaptureTask implements ProcessTask {

    private final RestClient http;

    public CountingCaptureTask(RestClient.Builder builder,
                               @Value("${openwcs.process-designer.counting-base-url:http://localhost:8095}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    @Override
    public String type() {
        return "counting.capture";
    }

    @Override
    public TaskSpec spec() {
        return new TaskSpec("counting.capture", "Capture count",
                java.util.List.of(TaskSpec.req("taskId"), TaskSpec.req("lineId"),
                        TaskSpec.req("countedQty")),
                java.util.List.of(TaskSpec.out("outcome"), TaskSpec.out("message")));
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> in) {
        String taskId = TaskInputs.requireUuid(in, "taskId").toString();
        String lineId = TaskInputs.requireUuid(in, "lineId").toString();
        BigDecimal countedQty = TaskInputs.decimal(in, "countedQty");
        if (countedQty == null) {
            throw new TaskExecutionException("Missing required task input: countedQty");
        }

        Map<String, Object> body = new HashMap<>();
        body.put("countedQty", countedQty);

        Map<String, Object> response = TaskHttp.postForMap(http,
                "/api/counting/tasks/" + taskId + "/lines/" + lineId + "/station-count", body, type());
        Map<String, Object> out = new HashMap<>();
        if (response.get("outcome") != null) {
            out.put("outcome", response.get("outcome"));
        }
        if (response.get("message") != null) {
            out.put("message", response.get("message"));
        }
        return out;
    }
}
