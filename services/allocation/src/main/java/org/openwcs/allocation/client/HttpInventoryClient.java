package org.openwcs.allocation.client;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** {@link InventoryClient} backed by the inventory REST API. */
@Component
public class HttpInventoryClient implements InventoryClient {

    private final RestClient http;

    public HttpInventoryClient(RestClient.Builder builder,
                               @Value("${openwcs.allocation.inventory-base-url:http://localhost:8082}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    @Override
    public BigDecimal availableAtLocation(UUID warehouseId, UUID skuId, UUID locationId) {
        Availability availability = http.get()
                .uri("/api/inventory/availability?warehouseId={w}&skuId={s}&locationId={l}",
                        warehouseId, skuId, locationId)
                .retrieve()
                .body(Availability.class);
        return availability == null || availability.availableToPromise() == null
                ? BigDecimal.ZERO
                : availability.availableToPromise();
    }

    @Override
    public UUID reserve(UUID warehouseId, UUID skuId, BigDecimal qty, UUID locationId, String orderRef) {
        Map<String, Object> body = new HashMap<>();
        body.put("warehouseId", warehouseId);
        body.put("skuId", skuId);
        body.put("qty", qty);
        body.put("locationId", locationId);
        body.put("orderRef", orderRef);
        ReservationResponse response = http.post()
                .uri("/api/inventory/reservations")
                .body(body)
                .retrieve()
                .body(ReservationResponse.class);
        return response == null ? null : response.id();
    }

    @Override
    public void release(UUID reservationId) {
        http.post()
                .uri("/api/inventory/reservations/{id}/release", reservationId)
                .retrieve()
                .toBodilessEntity();
    }

    private record Availability(BigDecimal availableToPromise) {
    }

    private record ReservationResponse(UUID id, String status) {
    }
}
