package org.openwcs.processdesigner.task;

import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Curated task {@code picking.confirm}: confirm an operator pick against a pick-task line, calling
 * {@code POST /api/orders/pick-tasks/{lineId}/confirm} on order-management (see ConfirmPickRequest).
 * Reads {@code lineId} (required, path), {@code pickedQty} (required, body) plus optional
 * {@code short} and {@code locationId}. Returns the line's resulting {@code status} if present.
 */
@Component
public class PickingConfirmTask implements ProcessTask {

    private final RestClient http;

    public PickingConfirmTask(RestClient.Builder builder,
                              @Value("${openwcs.process-designer.orders-base-url:http://localhost:8084}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    @Override
    public String type() {
        return "picking.confirm";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> in) {
        String lineId = TaskInputs.requireUuid(in, "lineId").toString();
        Map<String, Object> body = new HashMap<>();
        body.put("pickedQty", TaskInputs.decimal(in, "pickedQty"));
        if (in.get("short") != null) {
            body.put("short", Boolean.valueOf(String.valueOf(in.get("short"))));
        }
        String loc = TaskInputs.string(in, "locationId");
        if (loc != null) {
            body.put("locationId", loc);
        }

        Map<String, Object> response = TaskHttp.postForMap(
                http, "/api/orders/pick-tasks/" + lineId + "/confirm", body, type());
        Map<String, Object> out = new HashMap<>();
        if (response.get("status") != null) {
            out.put("pickStatus", response.get("status"));
        }
        return out;
    }
}
