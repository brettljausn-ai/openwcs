package org.openwcs.orders.client;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** {@link TxLogClient} backed by the txlog append API. */
@Component
public class HttpTxLogClient implements TxLogClient {

    private final RestClient http;

    public HttpTxLogClient(RestClient.Builder builder,
                           @Value("${openwcs.orders.txlog-base-url:http://localhost:8086}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    @Override
    public UUID append(String streamId, String eventType, UUID correlationId, String actor,
                       Map<String, Object> payload) {
        Map<String, Object> body = new HashMap<>();
        body.put("streamId", streamId);
        body.put("eventType", eventType);
        body.put("correlationId", correlationId);
        body.put("actor", actor);
        body.put("payload", payload);
        EventResponse response = http.post()
                .uri("/api/txlog/events")
                .body(body)
                .retrieve()
                .body(EventResponse.class);
        return response == null ? null : response.eventId();
    }

    private record EventResponse(UUID eventId) {
    }
}
