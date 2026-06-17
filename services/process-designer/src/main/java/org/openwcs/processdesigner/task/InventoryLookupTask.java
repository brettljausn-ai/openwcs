package org.openwcs.processdesigner.task;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Curated task {@code inventory.lookup}: read the on-hand stock of a SKU into a variable so a process
 * can use it (e.g. set an expected quantity before a count). The {@code warehouseId} is auto-injected
 * from the running instance, so the designer only maps what the operator scans.
 *
 * <ul>
 *   <li>{@code huId} given: the on-hand of the SKU <em>on that handling unit</em>, summed from
 *       {@code GET /api/inventory/handling-units/{huId}/contents}.</li>
 *   <li>else {@code locationId} given: pick-location availability via
 *       {@code GET /api/inventory/availability?...&locationId=...}.</li>
 *   <li>else: warehouse-wide availability for the SKU.</li>
 * </ul>
 * Returns {@code onHand} (the quantity) to merge back into the data object.
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
        // warehouseId is auto-injected from the instance, so it is NOT a mapped input. The operator
        // scans the SKU (required) and a location OR a handling unit (either, both optional).
        return new TaskSpec("inventory.lookup", "Look up on-hand stock",
                "Read the on-hand stock of a SKU at a location or on a handling unit "
                        + "(e.g. to set an expected quantity before a count).",
                List.of(TaskSpec.req("skuId", "The SKU to look up"),
                        TaskSpec.opt("locationId", "Optional: a storage location to scope the lookup"),
                        TaskSpec.opt("huId", "Optional: a handling unit to scope the lookup")),
                List.of(TaskSpec.out("onHand", "The on-hand quantity found")));
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> in) {
        UUID warehouseId = TaskInputs.requireUuid(in, "warehouseId");
        UUID skuId = TaskInputs.requireUuid(in, "skuId");
        UUID huId = TaskInputs.uuid(in, "huId");
        UUID locationId = TaskInputs.uuid(in, "locationId");

        Map<String, Object> out = new HashMap<>();
        if (huId != null) {
            // Sum the SKU's stock lines riding on the handling unit (one line per batch/status).
            List<Map<String, Object>> contents = TaskHttp.getForList(http,
                    "/api/inventory/handling-units/{hu}/contents", type(), huId);
            BigDecimal sum = BigDecimal.ZERO;
            for (Map<String, Object> line : contents) {
                if (skuId.toString().equals(String.valueOf(line.get("skuId")))) {
                    Object qty = line.get("qty");
                    if (qty != null) {
                        sum = sum.add(new BigDecimal(String.valueOf(qty)));
                    }
                }
            }
            out.put("onHand", sum);
            return out;
        }

        String uri = locationId != null
                ? "/api/inventory/availability?warehouseId={w}&skuId={s}&locationId={l}"
                : "/api/inventory/availability?warehouseId={w}&skuId={s}";
        Object[] vars = locationId != null
                ? new Object[] { warehouseId, skuId, locationId }
                : new Object[] { warehouseId, skuId };
        Map<String, Object> response = TaskHttp.getForMap(http, uri, type(), vars);
        out.put("onHand", response.getOrDefault("onHand", 0));
        return out;
    }
}
