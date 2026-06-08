package org.openwcs.counting.client;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** {@link InventoryClient} backed by the inventory service's availability projection. */
@Component
public class HttpInventoryClient implements InventoryClient {

    private final RestClient http;

    public HttpInventoryClient(RestClient.Builder builder,
                               @Value("${openwcs.counting.inventory-base-url:http://localhost:8082}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    @Override
    public BigDecimal expectedOnHand(UUID warehouseId, UUID skuId, UUID locationId) {
        Availability a = http.get()
                .uri("/api/inventory/availability?warehouseId={w}&skuId={s}&locationId={l}",
                        warehouseId, skuId, locationId)
                .retrieve()
                .body(Availability.class);
        return a == null || a.onHand() == null ? BigDecimal.ZERO : a.onHand();
    }

    @Override
    public List<StockCell> listStockCells(UUID warehouseId) {
        List<OverviewRow> rows = http.get()
                .uri("/api/inventory/stock/overview?warehouseId={w}", warehouseId)
                .retrieve()
                .body(new ParameterizedTypeReference<List<OverviewRow>>() {
                });
        if (rows == null) {
            return List.of();
        }
        return rows.stream()
                .filter(r -> r.locationId() != null && r.skuId() != null)
                .map(r -> new StockCell(r.locationId(), r.skuId()))
                .distinct()
                .toList();
    }

    /** Subset of the inventory service's Availability response. */
    private record Availability(BigDecimal onHand) {
    }

    /** Subset of the inventory stock-overview row. */
    private record OverviewRow(UUID skuId, UUID locationId) {
    }
}
