package org.openwcs.process.client;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** {@link OrderClient} backed by the order-management API. */
@Component
public class HttpOrderClient implements OrderClient {

    private final RestClient http;

    public HttpOrderClient(RestClient.Builder builder,
                           @Value("${openwcs.process.order-management-base-url:http://localhost:8084}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    @Override
    public void release(UUID orderId) {
        http.post().uri("/api/orders/{id}/release", orderId).retrieve().toBodilessEntity();
    }

    @Override
    public void releaseShort(UUID orderId) {
        http.post().uri("/api/orders/{id}/release-short", orderId).retrieve().toBodilessEntity();
    }
}
