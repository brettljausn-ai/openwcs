package org.openwcs.processdesigner.task;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Curated task {@code order.load}: resolve an order by its human-readable code (what the operator
 * scans / types) into the data object, calling {@code GET /api/orders/resolve?warehouseId=&ref=} on
 * order-management. The {@code warehouseId} is auto-injected from the running instance, so the
 * operator only enters the order code. Use this to load an order at the start of a flow (then branch
 * on {@code found} / {@code orderStatus}).
 *
 * <p>Reads {@code orderCode} (required). Returns {@code found}, and when found {@code orderId},
 * {@code orderRef}, {@code orderType}, {@code orderStatus}, {@code lineCount}.
 */
@Component
public class OrderLoadTask implements ProcessTask {

    private final RestClient http;

    public OrderLoadTask(RestClient.Builder builder,
                         @Value("${openwcs.process-designer.orders-base-url:http://localhost:8084}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    @Override
    public String type() {
        return "order.load";
    }

    @Override
    public TaskSpec spec() {
        // warehouseId is auto-injected from the running instance, so it is NOT a mapped input.
        return new TaskSpec("order.load", "Load order from order code",
                "Resolve an order by the code the operator scans / types (then branch on found "
                        + "or orderStatus), with the warehouse auto-injected from the running instance.",
                List.of(
                        TaskSpec.req("orderCode", "The order code the operator scanned or typed")),
                List.of(
                        TaskSpec.out("found", "True if an order with that code exists in the warehouse"),
                        TaskSpec.out("orderId", "The resolved order's id"),
                        TaskSpec.out("orderRef", "The order's human-readable reference"),
                        TaskSpec.out("orderType", "The order type, e.g. INBOUND or OUTBOUND"),
                        TaskSpec.out("orderStatus", "The order's current status"),
                        TaskSpec.out("lineCount", "The number of lines on the order")));
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> in) {
        String warehouseId = TaskInputs.requireUuid(in, "warehouseId").toString();
        String orderCode = TaskInputs.requireString(in, "orderCode");

        Map<String, Object> response = TaskHttp.getForMap(http,
                "/api/orders/resolve?warehouseId={w}&ref={r}", type(), warehouseId, orderCode);

        Map<String, Object> out = new HashMap<>();
        Object found = response.get("found");
        out.put("found", found instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(found)));
        Object order = response.get("order");
        if (order instanceof Map<?, ?> o) {
            putIfPresent(out, "orderId", o.get("orderId"));
            putIfPresent(out, "orderRef", o.get("orderRef"));
            putIfPresent(out, "orderType", o.get("orderType"));
            putIfPresent(out, "orderStatus", o.get("status"));
            putIfPresent(out, "lineCount", o.get("lineCount"));
        }
        return out;
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
