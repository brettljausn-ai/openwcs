package org.openwcs.integration.host.client;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** {@link OrderManagementClient} backed by the order-management REST API. */
@Component
public class HttpOrderManagementClient implements OrderManagementClient {

    private final RestClient http;

    public HttpOrderManagementClient(RestClient.Builder builder,
                                     @Value("${openwcs.host.order-management-base-url:http://localhost:8084}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    @Override
    public CreatedOrder createOrder(Map<String, Object> orderBody) {
        return http.post()
                .uri("/api/orders")
                .body(orderBody)
                .retrieve()
                .body(CreatedOrder.class);
    }
}
