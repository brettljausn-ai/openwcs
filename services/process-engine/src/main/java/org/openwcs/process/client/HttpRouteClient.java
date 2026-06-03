package org.openwcs.process.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** {@link RouteClient} backed by the flow-orchestrator conveyor-routing API. */
@Component
public class HttpRouteClient implements RouteClient {

    private final RestClient http;

    public HttpRouteClient(RestClient.Builder builder,
                           @Value("${openwcs.process.flow-orchestrator-base-url:http://localhost:8085}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    @Override
    public void assignRoute(UUID warehouseId, String barcode, List<String> targets) {
        Map<String, Object> body = new HashMap<>();
        body.put("warehouseId", warehouseId);
        body.put("barcode", barcode);
        body.put("targets", targets);
        http.post().uri("/api/flow/conveyor/routes").body(body).retrieve().toBodilessEntity();
    }
}
