package org.openwcs.orders.client;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** {@link MasterDataClient} backed by the master-data catalog REST API (lookup by code). */
@Component
public class HttpMasterDataClient implements MasterDataClient {

    private final RestClient http;

    public HttpMasterDataClient(RestClient.Builder builder,
                                @Value("${openwcs.orders.master-data-base-url:http://localhost:8081}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    @Override
    public boolean shippingServiceExists(String code) {
        return activeExists("/api/master-data/shipping-services", code);
    }

    @Override
    public boolean routeExists(String code) {
        return activeExists("/api/master-data/routes", code);
    }

    private boolean activeExists(String path, String code) {
        List<CatalogEntry> hits = http.get()
                .uri(uri -> uri.path(path).queryParam("code", code).build())
                .retrieve()
                .body(new ParameterizedTypeReference<List<CatalogEntry>>() {
                });
        return hits != null && hits.stream()
                .anyMatch(e -> code.equals(e.code()) && !"ARCHIVED".equals(e.status()));
    }

    private record CatalogEntry(String code, String status) {
    }
}
