package org.openwcs.slotting.client;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** {@link InventoryClient} backed by the inventory service's availability projection. */
@Component
public class HttpInventoryClient implements InventoryClient {

    private final RestClient http;

    public HttpInventoryClient(RestClient.Builder builder,
                               @Value("${openwcs.slotting.inventory-base-url:http://localhost:8082}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    @Override
    public BigDecimal onHandAtLocation(UUID warehouseId, UUID skuId, UUID locationId) {
        Availability a = http.get()
                .uri("/api/inventory/availability?warehouseId={w}&skuId={s}&locationId={l}", warehouseId, skuId, locationId)
                .retrieve()
                .body(Availability.class);
        return a == null || a.onHand() == null ? BigDecimal.ZERO : a.onHand();
    }

    /** Subset of the inventory service's Availability response. */
    private record Availability(BigDecimal onHand) {
    }
}
