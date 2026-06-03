package org.openwcs.integration.sap.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** {@link MasterDataClient} backed by the master-data Route catalog REST API (upsert by code). */
@Component
public class HttpMasterDataClient implements MasterDataClient {

    private final RestClient http;

    public HttpMasterDataClient(RestClient.Builder builder,
                                @Value("${openwcs.integration.master-data-base-url:http://localhost:8081}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    @Override
    public UpsertResult upsertRoute(RouteDto route) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", route.code());
        body.put("name", route.name());
        body.put("region", route.region());
        body.put("hostRef", route.hostRef());

        RouteResponse existing = findByCode(route.code());
        if (existing != null) {
            body.put("status", existing.status() == null ? "ACTIVE" : existing.status());
            http.put().uri("/api/master-data/routes/{id}", existing.id())
                    .body(body).retrieve().toBodilessEntity();
            return UpsertResult.UPDATED;
        }
        http.post().uri("/api/master-data/routes").body(body).retrieve().toBodilessEntity();
        return UpsertResult.CREATED;
    }

    private RouteResponse findByCode(String code) {
        List<RouteResponse> hits = http.get()
                .uri(uri -> uri.path("/api/master-data/routes").queryParam("code", code).build())
                .retrieve()
                .body(new ParameterizedTypeReference<List<RouteResponse>>() {
                });
        return hits == null || hits.isEmpty() ? null : hits.get(0);
    }

    private record RouteResponse(String id, String code, String status) {
    }
}
