package org.openwcs.processdesigner.task;

import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Curated task {@code inventory.move}: physically move a handling unit, calling
 * {@code POST /api/flow/moves} on flow-orchestrator (see FlowMoveRequest). Reads {@code warehouseId},
 * {@code huId}, {@code toLocationId} (required) plus optional {@code huCode}, {@code fromLocationId},
 * {@code family}, {@code destFamily}, {@code reason} from the step inputs. Returns the move's
 * {@code moveId} / {@code status} if present in the response.
 */
@Component
public class InventoryMoveTask implements ProcessTask {

    private final RestClient http;

    public InventoryMoveTask(RestClient.Builder builder,
                             @Value("${openwcs.process-designer.flow-base-url:http://localhost:8085}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    @Override
    public String type() {
        return "inventory.move";
    }

    @Override
    public TaskSpec spec() {
        return new TaskSpec("inventory.move", "Move handling unit",
                "Dispatch a physical move of a handling unit to a destination location "
                        + "(e.g. to relocate a tote after a process step).",
                java.util.List.of(
                        TaskSpec.req("warehouseId", "The warehouse the move runs in"),
                        TaskSpec.req("huId", "The handling unit to move"),
                        TaskSpec.req("toLocationId", "The destination location to move the handling unit to"),
                        TaskSpec.opt("huCode", "Optional: the handling unit's human-readable code"),
                        TaskSpec.opt("fromLocationId", "Optional: the source location the handling unit is leaving"),
                        TaskSpec.opt("family", "Optional: the source equipment / location family"),
                        TaskSpec.opt("destFamily", "Optional: the destination equipment / location family"),
                        TaskSpec.opt("reason", "Optional: a free-text reason recorded with the move")),
                java.util.List.of(
                        TaskSpec.out("moveId", "The id of the dispatched move"),
                        TaskSpec.out("status", "The move's current status")));
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> in) {
        Map<String, Object> body = new HashMap<>();
        body.put("warehouseId", TaskInputs.requireUuid(in, "warehouseId").toString());
        body.put("huId", TaskInputs.requireUuid(in, "huId").toString());
        body.put("toLocationId", TaskInputs.requireUuid(in, "toLocationId").toString());
        putIfPresent(body, "huCode", TaskInputs.string(in, "huCode"));
        putIfPresent(body, "fromLocationId", TaskInputs.string(in, "fromLocationId"));
        putIfPresent(body, "family", TaskInputs.string(in, "family"));
        putIfPresent(body, "destFamily", TaskInputs.string(in, "destFamily"));
        putIfPresent(body, "reason", TaskInputs.string(in, "reason"));

        Map<String, Object> response = TaskHttp.postForMap(http, "/api/flow/moves", body, type());
        Map<String, Object> out = new HashMap<>();
        putIfPresent(out, "moveId", response.get("moveId"));
        putIfPresent(out, "status", response.get("status"));
        return out;
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
