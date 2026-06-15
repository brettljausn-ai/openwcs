package org.openwcs.assistant.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Read-only HTTP client backing the assistant's tools. Each method is a GET against an existing
 * service; the caller's identity headers ({@code X-Auth-User}/{@code X-Auth-Roles}/
 * {@code X-Auth-Warehouses}) ride along automatically via the shared RestClient interceptor
 * ({@link org.openwcs.assistant.client.IdentityForwardingConfig}), so downstream RBAC + warehouse
 * scope still apply. Failures (service down, 4xx/5xx) are caught and returned as a compact JSON error
 * string so the tool loop can keep going and Claude can tell the user the data was unavailable.
 */
@Component
public class WarehouseToolClient {

    private static final Logger log = LoggerFactory.getLogger(WarehouseToolClient.class);

    private final RestClient orders;
    private final RestClient inventory;
    private final RestClient flow;
    private final RestClient allocation;

    public WarehouseToolClient(
            RestClient.Builder builder,
            @Value("${openwcs.assistant.order-management-base-url:http://localhost:8084}") String ordersUrl,
            @Value("${openwcs.assistant.inventory-base-url:http://localhost:8082}") String inventoryUrl,
            @Value("${openwcs.assistant.flow-orchestrator-base-url:http://localhost:8085}") String flowUrl,
            @Value("${openwcs.assistant.allocation-base-url:http://localhost:8091}") String allocationUrl) {
        this.orders = builder.clone().baseUrl(ordersUrl).build();
        this.inventory = builder.clone().baseUrl(inventoryUrl).build();
        this.flow = builder.clone().baseUrl(flowUrl).build();
        this.allocation = builder.clone().baseUrl(allocationUrl).build();
    }

    /** GET /api/orders?warehouseId=&status=&size= — find orders by status/direction. */
    public String searchOrders(String warehouseId, String status, Integer size) {
        StringBuilder uri = new StringBuilder("/api/orders?warehouseId=").append(enc(warehouseId));
        if (status != null && !status.isBlank()) {
            uri.append("&status=").append(enc(status));
        }
        uri.append("&size=").append(size != null && size > 0 ? Math.min(size, 50) : 20);
        return get(orders, uri.toString(), "search_orders");
    }

    /** GET /api/inventory/reports/stock-by-sku?warehouseId= — stock availability per SKU. */
    public String stockBySku(String warehouseId) {
        return get(inventory, "/api/inventory/reports/stock-by-sku?warehouseId=" + enc(warehouseId), "get_stock_by_sku");
    }

    /** GET /api/inventory/reports/dashboard?warehouseId= — utilisation, HU/SKU counts. */
    public String inventoryDashboard(String warehouseId) {
        return get(inventory, "/api/inventory/reports/dashboard?warehouseId=" + enc(warehouseId), "get_inventory_dashboard");
    }

    /** GET /api/flow/hu-trace?huId=&warehouseId= — where a handling unit is / its transport timeline. */
    public String huTrace(String huId, String warehouseId) {
        String uri = "/api/flow/hu-trace?huId=" + enc(huId);
        if (warehouseId != null && !warehouseId.isBlank()) {
            uri += "&warehouseId=" + enc(warehouseId);
        }
        return get(flow, uri, "get_hu_trace");
    }

    /** GET /api/flow/device-tasks?warehouseId=&status=&limit= — recent transport/device tasks. */
    public String transportTasks(String warehouseId, String status, Integer limit) {
        StringBuilder uri = new StringBuilder("/api/flow/device-tasks?warehouseId=").append(enc(warehouseId));
        if (status != null && !status.isBlank()) {
            uri.append("&status=").append(enc(status));
        }
        uri.append("&limit=").append(limit != null && limit > 0 ? Math.min(limit, 100) : 20);
        return get(flow, uri.toString(), "get_transport_tasks");
    }

    /** GET /api/allocation/reports/stock-blocking?warehouseId= — what's blocking outbound. */
    public String stockBlocking(String warehouseId) {
        return get(allocation, "/api/allocation/reports/stock-blocking?warehouseId=" + enc(warehouseId), "get_stock_blocking");
    }

    /** GET /api/orders/reports/dashboard?warehouseId= — inbound/outbound headline. */
    public String ordersDashboard(String warehouseId) {
        return get(orders, "/api/orders/reports/dashboard?warehouseId=" + enc(warehouseId), "get_dashboard");
    }

    private String get(RestClient client, String uri, String tool) {
        try {
            String body = client.get().uri(uri).retrieve().body(String.class);
            if (body == null || body.isBlank()) {
                return "{\"result\":null}";
            }
            // Keep the tool result compact: pass through the service JSON, truncated if very large.
            return truncate(body);
        } catch (Exception e) {
            log.warn("tool {} failed ({}): {}", tool, uri, e.getClass().getSimpleName());
            return "{\"error\":\"data unavailable for this request\"}";
        }
    }

    private static String truncate(String s) {
        int max = 12000;
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "\"...(truncated)\"";
    }

    private static String enc(String s) {
        // Values here are UUIDs / enum names / small ints from a typed request; pass through.
        return s == null ? "" : s.trim();
    }
}
