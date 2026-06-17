package org.openwcs.processdesigner.task;

import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Curated task {@code order.lookup}: read an order header into the data object, calling
 * {@code GET /api/orders/{id}} on order-management (see {@code OrderView}). Reads {@code orderId}
 * (required, path). Returns the order's {@code orderRef}, {@code orderType}, {@code status} and
 * {@code lineCount} so a flow can show or branch on order detail (e.g. block a goods-in against a
 * cancelled order).
 */
@Component
public class OrderLookupTask implements ProcessTask {

    private final RestClient http;

    public OrderLookupTask(RestClient.Builder builder,
                           @Value("${openwcs.process-designer.orders-base-url:http://localhost:8084}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    @Override
    public String type() {
        return "order.lookup";
    }

    @Override
    public TaskSpec spec() {
        return new TaskSpec("order.lookup", "Look up order",
                "Read an order header into the data object so a flow can show or branch on order "
                        + "detail (e.g. block a goods-in against a cancelled order).",
                java.util.List.of(
                        TaskSpec.req("orderId", "The order to look up")),
                java.util.List.of(
                        TaskSpec.out("orderRef", "The order's human-readable reference"),
                        TaskSpec.out("orderType", "The order type, e.g. INBOUND or OUTBOUND"),
                        TaskSpec.out("orderStatus", "The order's current status"),
                        TaskSpec.out("lineCount", "The number of lines on the order")));
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> in) {
        String orderId = TaskInputs.requireUuid(in, "orderId").toString();

        Map<String, Object> response =
                TaskHttp.getForMap(http, "/api/orders/" + orderId, type());

        Map<String, Object> out = new HashMap<>();
        putIfPresent(out, "orderRef", response.get("orderRef"));
        putIfPresent(out, "orderType", response.get("orderType"));
        putIfPresent(out, "orderStatus", response.get("status"));
        Object lines = response.get("lines");
        if (lines instanceof java.util.List<?> list) {
            out.put("lineCount", list.size());
        }
        return out;
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
