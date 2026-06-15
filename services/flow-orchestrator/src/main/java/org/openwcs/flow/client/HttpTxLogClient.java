package org.openwcs.flow.client;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * {@link TxLogClient} backed by the txlog service's append API ({@code POST /api/txlog/events}),
 * mirroring counting's client. Bounded by short timeouts: the append runs as a best-effort audit
 * side effect of the device-task lifecycle, so a slow or unreachable txlog must never stall a
 * transport callback.
 */
@Component
public class HttpTxLogClient implements TxLogClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final RestClient http;

    public HttpTxLogClient(RestClient.Builder builder, FlowProperties properties) {
        var factory = ClientHttpRequestFactories.get(
                ClientHttpRequestFactorySettings.DEFAULTS.withConnectTimeout(TIMEOUT).withReadTimeout(TIMEOUT));
        this.http = builder.baseUrl(properties.getTxlogBaseUrl()).requestFactory(factory).build();
    }

    @Override
    public UUID append(String streamId, String eventType, UUID correlationId, String actor,
                       Map<String, Object> payload) {
        Map<String, Object> body = new HashMap<>();
        body.put("streamId", streamId);
        body.put("eventType", eventType);
        body.put("occurredAt", Instant.now());
        body.put("actor", actor == null ? "flow-orchestrator" : actor);
        body.put("correlationId", correlationId);
        body.put("payload", payload);
        body.put("payloadVersion", 1);

        EventView event = http.post()
                .uri("/api/txlog/events")
                .body(body)
                .retrieve()
                .body(EventView.class);
        return event == null ? null : event.eventId();
    }

    /** Subset of the txlog append response. */
    private record EventView(UUID eventId) {
    }
}
