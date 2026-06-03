package org.openwcs.integration.manhattan.client;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Calls the canonical openWCS Host API. The Manhattan adapter is an anti-corruption layer: it
 * translates Manhattan Active messages into the vendor-neutral Host API rather than calling
 * domain services directly.
 */
@Component
public class HostApiClient {

    private final RestClient http;

    public HostApiClient(RestClient.Builder builder,
                         @Value("${openwcs.integration.host-api-base-url:http://localhost:8092}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    public void createOrder(Map<String, Object> hostOrder) {
        http.post().uri("/api/host/orders").body(hostOrder).retrieve().toBodilessEntity();
    }

    public void createAsn(Map<String, Object> hostAsn) {
        http.post().uri("/api/host/asns").body(hostAsn).retrieve().toBodilessEntity();
    }
}
