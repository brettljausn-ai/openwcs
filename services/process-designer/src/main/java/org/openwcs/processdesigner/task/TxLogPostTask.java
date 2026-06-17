package org.openwcs.processdesigner.task;

import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Curated task {@code txlog.post}: append a stock transaction / event, calling
 * {@code POST /api/txlog/events} on txlog (see AppendEventRequest). Reads {@code streamId} and
 * {@code eventType} (required); any remaining inputs are carried as the event {@code payload}. The
 * {@code actor} is the forwarded operator (defaults to {@code process-designer} if absent). Returns
 * the appended event's {@code eventId}.
 *
 * <p>This is the task the seeded Stock Check process uses to record a count as a
 * {@code StockCounted} event.
 */
@Component
public class TxLogPostTask implements ProcessTask {

    private static final java.util.Set<String> RESERVED =
            java.util.Set.of("streamId", "eventType", "actor", "correlationId");

    private final RestClient http;

    public TxLogPostTask(RestClient.Builder builder,
                         @Value("${openwcs.process-designer.txlog-base-url:http://localhost:8086}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    @Override
    public String type() {
        return "txlog.post";
    }

    @Override
    public TaskSpec spec() {
        // streamId + eventType are required; any other mapped input becomes the event payload.
        return new TaskSpec("txlog.post", "Post stock transaction",
                "Append a stock transaction / event to the transaction log; any extra mapped inputs "
                        + "are carried as the event payload (e.g. to record a count as a StockCounted event).",
                java.util.List.of(
                        TaskSpec.req("streamId", "The event-stream key to append to (e.g. the SKU or HU id)"),
                        TaskSpec.req("eventType", "The event type to record, e.g. StockCounted"),
                        TaskSpec.opt("actor", "Optional: the actor to attribute; defaults to the forwarded operator")),
                java.util.List.of(
                        TaskSpec.out("eventId", "The id of the appended event")));
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> in) {
        Map<String, Object> body = new HashMap<>();
        body.put("streamId", TaskInputs.requireString(in, "streamId"));
        body.put("eventType", TaskInputs.requireString(in, "eventType"));
        String actor = TaskInputs.string(in, "actor");
        body.put("actor", actor == null || actor.isBlank() ? "process-designer" : actor);

        // Everything not a reserved field becomes the event payload.
        Map<String, Object> payload = new HashMap<>();
        for (Map.Entry<String, Object> e : in.entrySet()) {
            if (!RESERVED.contains(e.getKey())) {
                payload.put(e.getKey(), e.getValue());
            }
        }
        body.put("payload", payload);

        Map<String, Object> response = TaskHttp.postForMap(http, "/api/txlog/events", body, type());
        Map<String, Object> out = new HashMap<>();
        if (response.get("eventId") != null) {
            out.put("eventId", response.get("eventId"));
        }
        return out;
    }
}
