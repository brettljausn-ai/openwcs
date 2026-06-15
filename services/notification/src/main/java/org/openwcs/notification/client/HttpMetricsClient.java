package org.openwcs.notification.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * {@link MetricsClient} backed by the sibling REST APIs. Every fetch is wrapped so a source being
 * down (connection refused, 5xx, 404 while the parallel-built endpoint is missing) logs a WARN and
 * returns null/empty rather than throwing — the evaluator then simply skips that metric this tick.
 */
@Component
public class HttpMetricsClient implements MetricsClient {

    private static final Logger log = LoggerFactory.getLogger(HttpMetricsClient.class);

    private final RestClient masterData;
    private final RestClient flow;
    private final RestClient inventory;
    private final RestClient allocation;
    private final RestClient slotting;
    private final RestClient orders;
    private final List<UUID> configuredWarehouseIds;

    public HttpMetricsClient(
            RestClient.Builder builder,
            @Value("${openwcs.notification.master-data-base-url:http://localhost:8081}") String masterDataUrl,
            @Value("${openwcs.notification.flow-base-url:http://localhost:8085}") String flowUrl,
            @Value("${openwcs.notification.inventory-base-url:http://localhost:8082}") String inventoryUrl,
            @Value("${openwcs.notification.allocation-base-url:http://localhost:8091}") String allocationUrl,
            @Value("${openwcs.notification.slotting-base-url:http://localhost:8093}") String slottingUrl,
            @Value("${openwcs.notification.order-management-base-url:http://localhost:8084}") String ordersUrl,
            @Value("${openwcs.notification.warehouse-ids:}") String warehouseIds) {
        this.masterData = builder.clone().baseUrl(masterDataUrl).build();
        this.flow = builder.clone().baseUrl(flowUrl).build();
        this.inventory = builder.clone().baseUrl(inventoryUrl).build();
        this.allocation = builder.clone().baseUrl(allocationUrl).build();
        this.slotting = builder.clone().baseUrl(slottingUrl).build();
        this.orders = builder.clone().baseUrl(ordersUrl).build();
        this.configuredWarehouseIds = parseUuids(warehouseIds);
    }

    @Override
    public List<UUID> warehouseIds() {
        if (!configuredWarehouseIds.isEmpty()) {
            return configuredWarehouseIds;
        }
        try {
            WarehousePage page = masterData.get()
                    .uri("/api/master-data/warehouses?size=1000")
                    .retrieve()
                    .body(WarehousePage.class);
            if (page == null || page.content() == null) {
                return List.of();
            }
            List<UUID> ids = new ArrayList<>();
            for (WarehouseRef w : page.content()) {
                if (w.id() != null) {
                    ids.add(w.id());
                }
            }
            return ids;
        } catch (Exception e) {
            log.warn("could not discover warehouses from master-data: {}", e.toString());
            return List.of();
        }
    }

    @Override
    public AlertThresholds thresholds() {
        return get(masterData, "/api/master-data/alert-thresholds", AlertThresholds.class, "master-data thresholds");
    }

    @Override
    public AutomationSummary automationSummary(UUID warehouseId) {
        return get(flow, "/api/flow/reports/automation-summary?warehouseId=" + warehouseId,
                AutomationSummary.class, "flow automation-summary");
    }

    @Override
    public InventoryDashboard inventoryDashboard(UUID warehouseId) {
        return get(inventory, "/api/inventory/reports/dashboard?warehouseId=" + warehouseId,
                InventoryDashboard.class, "inventory dashboard");
    }

    @Override
    public StockBlocking stockBlocking(UUID warehouseId) {
        return get(allocation, "/api/allocation/reports/stock-blocking?warehouseId=" + warehouseId,
                StockBlocking.class, "allocation stock-blocking");
    }

    @Override
    public ReplenishmentDashboard replenishmentDashboard(UUID warehouseId) {
        return get(slotting, "/api/slotting/replenishment/dashboard?warehouseId=" + warehouseId,
                ReplenishmentDashboard.class, "slotting replenishment dashboard");
    }

    @Override
    public OrdersDashboard ordersDashboard(UUID warehouseId) {
        return get(orders, "/api/orders/reports/dashboard?warehouseId=" + warehouseId,
                OrdersDashboard.class, "order-management dashboard");
    }

    private <T> T get(RestClient client, String uri, Class<T> type, String label) {
        try {
            return client.get().uri(uri).retrieve().body(type);
        } catch (Exception e) {
            log.warn("metric source unavailable ({}): {}", label, e.toString());
            return null;
        }
    }

    private static List<UUID> parseUuids(String csv) {
        List<UUID> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return out;
        }
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                try {
                    out.add(UUID.fromString(trimmed));
                } catch (IllegalArgumentException ignored) {
                    log.warn("ignoring malformed warehouse id in openwcs.notification.warehouse-ids: {}", trimmed);
                }
            }
        }
        return out;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WarehousePage(List<WarehouseRef> content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WarehouseRef(UUID id) {
    }
}
