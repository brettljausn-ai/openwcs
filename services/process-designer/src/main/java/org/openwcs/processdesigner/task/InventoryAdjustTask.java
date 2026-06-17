package org.openwcs.processdesigner.task;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Curated task {@code inventory.adjust}: post a {@code StockAdjusted} stock transaction directly to
 * the transaction log, calling {@code POST /api/txlog/events} on txlog (see {@code AppendEventRequest}).
 * The inventory projection consumes {@code StockAdjusted} and applies the signed delta. Reads
 * {@code warehouseId}, {@code skuId}, {@code qtyDelta} (required) plus optional {@code locationId},
 * {@code huId}, {@code batchId}, {@code status}, {@code uomCode}. The stream is keyed on the SKU; the
 * {@code actor} is the forwarded operator. Returns the appended event's {@code eventId}.
 *
 * <p>This is the txlog-native sibling of {@code host.confirm}: use {@code inventory.adjust} for an
 * internally-driven correction, {@code host.confirm} when the adjustment must also flow to the host.
 */
@Component
public class InventoryAdjustTask implements ProcessTask {

    private final RestClient http;

    public InventoryAdjustTask(RestClient.Builder builder,
                               @Value("${openwcs.process-designer.txlog-base-url:http://localhost:8086}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    @Override
    public String type() {
        return "inventory.adjust";
    }

    @Override
    public TaskSpec spec() {
        return new TaskSpec("inventory.adjust", "Adjust stock",
                "Post an internal StockAdjusted correction directly to the transaction log "
                        + "(use host.confirm when the adjustment must also flow to the host).",
                java.util.List.of(
                        TaskSpec.req("warehouseId", "The warehouse the adjustment applies to"),
                        TaskSpec.req("skuId", "The SKU being adjusted (also the event stream key)"),
                        TaskSpec.req("qtyDelta", "The signed quantity delta to apply (positive or negative)"),
                        TaskSpec.opt("locationId", "Optional: the location the adjustment applies to"),
                        TaskSpec.opt("huId", "Optional: the handling unit the adjustment applies to"),
                        TaskSpec.opt("batchId", "Optional: the batch / lot the adjustment applies to"),
                        TaskSpec.opt("status", "Optional: the stock status, e.g. AVAILABLE or BLOCKED"),
                        TaskSpec.opt("uomCode", "Optional: the unit-of-measure code for the quantity")),
                java.util.List.of(
                        TaskSpec.out("eventId", "The id of the appended StockAdjusted event")));
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> in) {
        String skuId = TaskInputs.requireUuid(in, "skuId").toString();
        BigDecimal qtyDelta = TaskInputs.decimal(in, "qtyDelta");
        if (qtyDelta == null) {
            throw new TaskExecutionException("Missing required task input: qtyDelta");
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("warehouseId", TaskInputs.requireUuid(in, "warehouseId").toString());
        payload.put("skuId", skuId);
        payload.put("qtyDelta", qtyDelta);
        putIfPresent(payload, "locationId", TaskInputs.string(in, "locationId"));
        putIfPresent(payload, "huId", TaskInputs.string(in, "huId"));
        putIfPresent(payload, "batchId", TaskInputs.string(in, "batchId"));
        putIfPresent(payload, "status", TaskInputs.string(in, "status"));
        putIfPresent(payload, "uomCode", TaskInputs.string(in, "uomCode"));

        String actor = TaskInputs.string(in, "actor");
        Map<String, Object> body = new HashMap<>();
        body.put("streamId", skuId);
        body.put("eventType", "StockAdjusted");
        body.put("actor", actor == null || actor.isBlank() ? "process-designer" : actor);
        body.put("payload", payload);

        Map<String, Object> response = TaskHttp.postForMap(http, "/api/txlog/events", body, type());
        Map<String, Object> out = new HashMap<>();
        putIfPresent(out, "eventId", response.get("eventId"));
        return out;
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
