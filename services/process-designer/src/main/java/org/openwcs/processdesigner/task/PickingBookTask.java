package org.openwcs.processdesigner.task;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Curated task {@code picking.book}: book picked stock from a location against an order's pick-task
 * line, calling {@code POST /api/orders/pick-tasks/{lineId}/confirm} on order-management (see
 * ConfirmPickRequest). Unlike {@code picking.confirm} this passes the running instance id
 * ({@code processInstanceId}, auto-injected) so the booking is tied to the process run, and it
 * surfaces the booked quantity. order-management clamps to availability and marks SHORT itself.
 *
 * <ul>
 *   <li>Reads {@code lineId}, {@code locationId}, {@code pickedQty} (required) plus optional
 *       {@code short}.</li>
 *   <li>Returns {@code bookedQty} (the quantity order-management actually booked), the
 *       {@code requestedQty} the operator entered, and {@code pickStatus} (the line's resulting
 *       status, e.g. SHORT).</li>
 * </ul>
 */
@Component
public class PickingBookTask implements ProcessTask {

    private final RestClient http;

    public PickingBookTask(RestClient.Builder builder,
                           @Value("${openwcs.process-designer.orders-base-url:http://localhost:8084}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    @Override
    public String type() {
        return "picking.book";
    }

    @Override
    public TaskSpec spec() {
        // processInstanceId is auto-injected from the running instance, so it is NOT a mapped input.
        return new TaskSpec("picking.book", "Book picked stock to order line",
                "Book picked stock from a location against an order's pick-task line, tying the "
                        + "booking to the running process (order-management clamps to availability and "
                        + "marks the line SHORT itself).",
                List.of(
                        TaskSpec.req("lineId", "The pick-task line being booked"),
                        TaskSpec.req("locationId", "The location the stock was picked from"),
                        TaskSpec.req("pickedQty", "The quantity the operator actually picked"),
                        TaskSpec.opt("short", "Optional: true to confirm the line as a short pick")),
                List.of(
                        TaskSpec.out("bookedQty", "The quantity order-management actually booked (clamped to available)"),
                        TaskSpec.out("requestedQty", "The quantity the operator entered"),
                        TaskSpec.out("pickStatus", "The line's resulting status after the booking, e.g. SHORT")));
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> in) {
        String lineId = TaskInputs.requireUuid(in, "lineId").toString();
        String locationId = TaskInputs.requireUuid(in, "locationId").toString();
        java.math.BigDecimal pickedQty = TaskInputs.decimal(in, "pickedQty");
        if (pickedQty == null) {
            throw new TaskExecutionException("Missing required task input: pickedQty");
        }

        Map<String, Object> body = new HashMap<>();
        body.put("pickedQty", pickedQty);
        body.put("locationId", locationId);
        if (in.get("short") != null) {
            body.put("short", Boolean.valueOf(String.valueOf(in.get("short"))));
        }
        // Tie the booking to the running process so order-management can attribute / audit it.
        String instanceId = TaskInputs.string(in, "instanceId");
        if (instanceId != null && !instanceId.isBlank()) {
            body.put("processInstanceId", instanceId);
        }

        Map<String, Object> response = TaskHttp.postForMap(
                http, "/api/orders/pick-tasks/" + lineId + "/confirm", body, type());

        Map<String, Object> out = new HashMap<>();
        // The returned pickedQty is what was actually booked (clamped to available).
        putIfPresent(out, "bookedQty", response.get("pickedQty"));
        out.put("requestedQty", pickedQty);
        putIfPresent(out, "pickStatus", response.get("status"));
        return out;
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
