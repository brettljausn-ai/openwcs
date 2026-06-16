package org.openwcs.processdesigner.task;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Curated task {@code host.confirm}: post a host-driven stock adjustment / confirmation back to the
 * canonical Host API, calling {@code POST /api/host/inventory/adjustments} on integration-host (see
 * {@code InventoryAdjustment}). The host service streams it as a {@code StockAdjusted} event and the
 * inventory projection applies the signed delta. Reads {@code warehouseId}, {@code skuId},
 * {@code qtyDelta} (required) plus optional {@code locationId}, {@code uomCode}, {@code status},
 * {@code reason}. Returns the appended event's {@code eventId} and {@code position}.
 */
@Component
public class HostConfirmTask implements ProcessTask {

    private final RestClient http;

    public HostConfirmTask(RestClient.Builder builder,
                           @Value("${openwcs.process-designer.integration-host-base-url:http://localhost:8092}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    @Override
    public String type() {
        return "host.confirm";
    }

    @Override
    public TaskSpec spec() {
        return new TaskSpec("host.confirm", "Confirm to host",
                java.util.List.of(TaskSpec.req("warehouseId"), TaskSpec.req("skuId"),
                        TaskSpec.req("qtyDelta"), TaskSpec.opt("locationId"), TaskSpec.opt("uomCode"),
                        TaskSpec.opt("status"), TaskSpec.opt("reason")),
                java.util.List.of(TaskSpec.out("eventId"), TaskSpec.out("position")));
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> in) {
        Map<String, Object> body = new HashMap<>();
        body.put("warehouseId", TaskInputs.requireUuid(in, "warehouseId").toString());
        body.put("skuId", TaskInputs.requireUuid(in, "skuId").toString());
        BigDecimal qtyDelta = TaskInputs.decimal(in, "qtyDelta");
        if (qtyDelta == null) {
            throw new TaskExecutionException("Missing required task input: qtyDelta");
        }
        body.put("qtyDelta", qtyDelta);
        putIfPresent(body, "locationId", TaskInputs.string(in, "locationId"));
        putIfPresent(body, "uomCode", TaskInputs.string(in, "uomCode"));
        putIfPresent(body, "status", TaskInputs.string(in, "status"));
        putIfPresent(body, "reason", TaskInputs.string(in, "reason"));

        Map<String, Object> response =
                TaskHttp.postForMap(http, "/api/host/inventory/adjustments", body, type());
        Map<String, Object> out = new HashMap<>();
        putIfPresent(out, "eventId", response.get("eventId"));
        putIfPresent(out, "position", response.get("position"));
        return out;
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
