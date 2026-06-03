package org.openwcs.integration.host.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** {@link TxLogClient} backed by the txlog global replay feed (GET /api/txlog/events?afterPosition=). */
@Component
public class HttpTxLogClient implements TxLogClient {

    private final RestClient http;

    public HttpTxLogClient(RestClient.Builder builder,
                           @Value("${openwcs.host.txlog-base-url:http://localhost:8086}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    @Override
    public List<TxEvent> feed(long afterPosition, int limit) {
        List<TxEvent> events = http.get()
                .uri(uri -> uri.path("/api/txlog/events")
                        .queryParam("afterPosition", afterPosition)
                        .queryParam("limit", limit)
                        .build())
                .retrieve()
                .body(new ParameterizedTypeReference<List<TxEvent>>() {
                });
        return events == null ? List.of() : events;
    }

    @Override
    public Appended append(String streamId, String eventType, String actor, Map<String, Object> payload) {
        Map<String, Object> body = new HashMap<>();
        body.put("streamId", streamId);
        body.put("eventType", eventType);
        body.put("actor", actor);
        body.put("payload", payload);
        return http.post()
                .uri("/api/txlog/events")
                .body(body)
                .retrieve()
                .body(Appended.class);
    }
}
