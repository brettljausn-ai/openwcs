package org.openwcs.processdesigner.task;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Curated task {@code inventory.reduce}: an exception correction that removes stock at a location for
 * a stated reason (e.g. damage, write-off). It posts a {@code StockAdjusted} event to the
 * transaction log with a NEGATIVE {@code qtyDelta} and the {@code reason}, attributing the forwarded
 * operator. Before posting it pre-checks availability via
 * {@code GET /api/inventory/availability?...} and rejects (502) if the reduction would exceed the
 * on-hand at the location, so it never emits a poison event or drives stock negative.
 *
 * <ul>
 *   <li>Reads {@code skuId}, {@code locationId}, {@code qty}, {@code reason} (required) plus optional
 *       {@code huId}, {@code batchId}. {@code warehouseId} is auto-injected from the running
 *       instance.</li>
 *   <li>Returns {@code eventId} (the appended StockAdjusted event).</li>
 * </ul>
 */
@Component
public class InventoryReduceTask implements ProcessTask {

    private final RestClient inventory;
    private final RestClient txlog;

    public InventoryReduceTask(
            RestClient.Builder builder,
            @Value("${openwcs.process-designer.inventory-base-url:http://localhost:8082}") String inventoryBaseUrl,
            @Value("${openwcs.process-designer.txlog-base-url:http://localhost:8086}") String txlogBaseUrl) {
        this.inventory = builder.clone().baseUrl(inventoryBaseUrl).build();
        this.txlog = builder.clone().baseUrl(txlogBaseUrl).build();
    }

    @Override
    public String type() {
        return "inventory.reduce";
    }

    @Override
    public TaskSpec spec() {
        // warehouseId is auto-injected from the running instance, so it is NOT a mapped input.
        return new TaskSpec("inventory.reduce", "Exception: reduce stock due to reason",
                "Remove stock at a location for a stated reason (e.g. damage, write-off): posts a "
                        + "negative StockAdjusted correction, after checking the reduction does not "
                        + "exceed the on-hand at the location.",
                List.of(
                        TaskSpec.req("skuId", "The SKU to reduce"),
                        TaskSpec.req("locationId", "The location to reduce the stock at"),
                        TaskSpec.req("qty", "The (positive) quantity to remove"),
                        TaskSpec.req("reason", "Why the stock is being removed, e.g. DAMAGED"),
                        TaskSpec.opt("huId", "Optional: the handling unit the reduction applies to"),
                        TaskSpec.opt("batchId", "Optional: the batch / lot the reduction applies to")),
                List.of(
                        TaskSpec.out("eventId", "The id of the appended StockAdjusted event")));
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> in) {
        String warehouseId = TaskInputs.requireUuid(in, "warehouseId").toString();
        String skuId = TaskInputs.requireUuid(in, "skuId").toString();
        String locationId = TaskInputs.requireUuid(in, "locationId").toString();
        BigDecimal qty = TaskInputs.decimal(in, "qty");
        if (qty == null) {
            throw new TaskExecutionException("Missing required task input: qty");
        }
        if (qty.signum() <= 0) {
            throw new TaskExecutionException("inventory.reduce qty must be positive (the quantity to remove): " + qty);
        }
        String reason = TaskInputs.requireString(in, "reason");

        // Pre-check on-hand at the location and reject an over-reduction before posting (never drives
        // stock negative / never emits a poison event).
        Map<String, Object> avail = TaskHttp.getForMap(inventory,
                "/api/inventory/availability?warehouseId={w}&skuId={s}&locationId={l}", type(),
                warehouseId, skuId, locationId);
        Object onHand = avail.get("onHand");
        if (onHand != null) {
            BigDecimal available = new BigDecimal(String.valueOf(onHand));
            if (qty.compareTo(available) > 0) {
                throw new TaskExecutionException("inventory.reduce of " + qty + " exceeds on-hand " + available
                        + " for SKU " + skuId + " at location " + locationId);
            }
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("warehouseId", warehouseId);
        payload.put("skuId", skuId);
        payload.put("locationId", locationId);
        payload.put("qtyDelta", qty.negate());
        payload.put("reason", reason);
        putIfPresent(payload, "huId", TaskInputs.string(in, "huId"));
        putIfPresent(payload, "batchId", TaskInputs.string(in, "batchId"));
        String instanceId = TaskInputs.string(in, "instanceId");
        putIfPresent(payload, "processInstanceId", instanceId);

        String actor = TaskInputs.string(in, "actor");
        Map<String, Object> body = new HashMap<>();
        body.put("streamId", skuId);
        body.put("eventType", "StockAdjusted");
        body.put("actor", actor == null || actor.isBlank() ? "process-designer" : actor);
        body.put("payload", payload);

        Map<String, Object> response = TaskHttp.postForMap(txlog, "/api/txlog/events", body, type());
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
