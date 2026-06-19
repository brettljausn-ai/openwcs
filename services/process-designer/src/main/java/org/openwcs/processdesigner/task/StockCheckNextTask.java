package org.openwcs.processdesigner.task;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Curated task {@code stockcheck.next}: claim the next open stock-check line in an area for the
 * running instance, calling {@code POST /api/counting/lines/claim-next} on counting. The
 * {@code warehouseId} and {@code instanceId} are auto-injected from the running instance (so the
 * operator only confirms the area), and the claim is tagged with the instance so abandoning the
 * process can later free it. Reads {@code areaId} (required) plus the injected {@code warehouseId} /
 * {@code instanceId}. Returns {@code found} and, when found, the claimed line's fields.
 */
@Component
public class StockCheckNextTask implements ProcessTask {

    private final RestClient http;

    public StockCheckNextTask(RestClient.Builder builder,
                              @Value("${openwcs.process-designer.counting-base-url:http://localhost:8095}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    @Override
    public String type() {
        return "stockcheck.next";
    }

    @Override
    public TaskSpec spec() {
        // warehouseId + instanceId are auto-injected from the instance, so they are NOT mapped inputs.
        return new TaskSpec("stockcheck.next", "Next stock-check line in area",
                "Claim the next open stock-check line in an area for this process run "
                        + "(returns found=false when the area has no more work).",
                List.of(TaskSpec.req("areaId", "The area to claim the next stock-check line in")),
                List.of(TaskSpec.out("found", "Whether a line was claimed (false means no more work in the area)"),
                        TaskSpec.out("countTaskId", "The count task the claimed line belongs to"),
                        TaskSpec.out("lineId", "The claimed stock-check line"),
                        TaskSpec.out("locationId", "The location to count"),
                        TaskSpec.out("skuId", "The SKU to count"),
                        TaskSpec.out("expectedQty", "The expected (system) quantity for the line"),
                        TaskSpec.out("uomCode", "The unit of measure for the count")));
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> in) {
        Map<String, Object> body = new HashMap<>();
        body.put("warehouseId", TaskInputs.requireUuid(in, "warehouseId").toString());
        body.put("areaId", TaskInputs.requireUuid(in, "areaId").toString());
        body.put("instanceId", TaskInputs.requireString(in, "instanceId"));

        Map<String, Object> response = TaskHttp.postForMap(http, "/api/counting/lines/claim-next", body, type());

        Map<String, Object> out = new HashMap<>();
        out.put("found", response.get("found") instanceof Boolean b && b);
        putIfPresent(out, "countTaskId", response.get("countTaskId"));
        putIfPresent(out, "lineId", response.get("lineId"));
        putIfPresent(out, "locationId", response.get("locationId"));
        putIfPresent(out, "skuId", response.get("skuId"));
        putIfPresent(out, "expectedQty", response.get("expectedQty"));
        putIfPresent(out, "uomCode", response.get("uomCode"));
        return out;
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
