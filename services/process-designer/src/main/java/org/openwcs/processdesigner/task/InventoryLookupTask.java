package org.openwcs.processdesigner.task;

import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Curated task {@code inventory.lookup}: read on-hand availability into a variable, calling
 * {@code GET /api/inventory/availability} on inventory. Reads {@code warehouseId}, {@code skuId},
 * {@code locationId} (required). Returns {@code onHand} (the available quantity) to merge back into
 * the data object.
 */
@Component
public class InventoryLookupTask implements ProcessTask {

    private final RestClient http;

    public InventoryLookupTask(RestClient.Builder builder,
                               @Value("${openwcs.process-designer.inventory-base-url:http://localhost:8082}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    @Override
    public String type() {
        return "inventory.lookup";
    }

    @Override
    public TaskSpec spec() {
        return new TaskSpec("inventory.lookup", "Look up on-hand",
                java.util.List.of(TaskSpec.req("warehouseId"), TaskSpec.req("skuId"),
                        TaskSpec.req("locationId")),
                java.util.List.of(TaskSpec.out("onHand")));
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> in) {
        String warehouseId = TaskInputs.requireUuid(in, "warehouseId").toString();
        String skuId = TaskInputs.requireUuid(in, "skuId").toString();
        String locationId = TaskInputs.requireUuid(in, "locationId").toString();

        Map<String, Object> response = TaskHttp.getForMap(http,
                "/api/inventory/availability?warehouseId={w}&skuId={s}&locationId={l}",
                type(), warehouseId, skuId, locationId);

        Map<String, Object> out = new HashMap<>();
        out.put("onHand", response.getOrDefault("onHand", 0));
        return out;
    }
}
