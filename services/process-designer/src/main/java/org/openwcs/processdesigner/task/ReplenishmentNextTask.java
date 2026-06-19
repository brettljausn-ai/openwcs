package org.openwcs.processdesigner.task;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Curated task {@code replenishment.next}: claim the next open replenishment (collection) task in an
 * area for the running instance, calling {@code POST /api/slotting/replenishment/claim-next} on
 * slotting. The {@code warehouseId} and {@code instanceId} are auto-injected from the running
 * instance (so the operator only confirms the area), and the claim is tagged with the instance so
 * abandoning the process can later free it. Reads {@code areaId} (required) plus the injected
 * {@code warehouseId} / {@code instanceId}. Returns {@code found} and, when found, the claimed task.
 */
@Component
public class ReplenishmentNextTask implements ProcessTask {

    private final RestClient http;

    public ReplenishmentNextTask(RestClient.Builder builder,
                                 @Value("${openwcs.process-designer.slotting-base-url:http://localhost:8093}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    @Override
    public String type() {
        return "replenishment.next";
    }

    @Override
    public TaskSpec spec() {
        // warehouseId + instanceId are auto-injected from the instance, so they are NOT mapped inputs.
        return new TaskSpec("replenishment.next", "Next collection line in area",
                "Claim the next open replenishment (collection) task in an area for this process run "
                        + "(returns found=false when the area has no more work).",
                List.of(TaskSpec.req("areaId", "The area to claim the next replenishment task in")),
                List.of(TaskSpec.out("found", "Whether a task was claimed (false means no more work in the area)"),
                        TaskSpec.out("taskId", "The claimed replenishment task"),
                        TaskSpec.out("skuId", "The SKU to move"),
                        TaskSpec.out("fromLocationId", "The source location to collect from"),
                        TaskSpec.out("toLocationId", "The destination location to replenish"),
                        TaskSpec.out("qty", "The quantity to move"),
                        TaskSpec.out("uomId", "The unit of measure for the quantity"),
                        TaskSpec.out("priority", "The task's priority")));
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> in) {
        Map<String, Object> body = new HashMap<>();
        body.put("warehouseId", TaskInputs.requireUuid(in, "warehouseId").toString());
        body.put("areaId", TaskInputs.requireUuid(in, "areaId").toString());
        body.put("instanceId", TaskInputs.requireString(in, "instanceId"));

        Map<String, Object> response = TaskHttp.postForMap(http,
                "/api/slotting/replenishment/claim-next", body, type());

        Map<String, Object> out = new HashMap<>();
        out.put("found", response.get("found") instanceof Boolean b && b);
        putIfPresent(out, "taskId", response.get("taskId"));
        putIfPresent(out, "skuId", response.get("skuId"));
        putIfPresent(out, "fromLocationId", response.get("fromLocationId"));
        putIfPresent(out, "toLocationId", response.get("toLocationId"));
        putIfPresent(out, "qty", response.get("qty"));
        putIfPresent(out, "uomId", response.get("uomId"));
        putIfPresent(out, "priority", response.get("priority"));
        return out;
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
