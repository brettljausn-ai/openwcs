package org.openwcs.orders.client;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * {@link InventoryClient} backed by the inventory service REST API. Identity is forwarded onto
 * the call by {@link IdentityForwardingConfig} (the auto-configured builder is customized), so
 * the inventory RBAC check sees the original operator.
 */
@Component
public class HttpInventoryClient implements InventoryClient {

    private final RestClient http;

    public HttpInventoryClient(
            RestClient.Builder builder,
            @Value("${openwcs.order-management.inventory-base-url:http://localhost:8082}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    @Override
    public Availability availabilityAtLocation(UUID warehouseId, UUID skuId, UUID locationId) {
        AvailabilityResponse response = http.get()
                .uri(uri -> uri.path("/api/inventory/availability")
                        .queryParam("warehouseId", warehouseId)
                        .queryParam("skuId", skuId)
                        .queryParam("locationId", locationId)
                        .build())
                .retrieve()
                .body(AvailabilityResponse.class);
        if (response == null) {
            return new Availability(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        return new Availability(
                response.onHand() == null ? BigDecimal.ZERO : response.onHand(),
                response.reserved() == null ? BigDecimal.ZERO : response.reserved(),
                response.availableToPromise() == null ? BigDecimal.ZERO : response.availableToPromise());
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record AvailabilityResponse(BigDecimal onHand, BigDecimal reserved, BigDecimal availableToPromise) {
    }
}
