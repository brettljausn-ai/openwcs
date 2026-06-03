package org.openwcs.integration.host.client;

import java.util.List;
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
}
