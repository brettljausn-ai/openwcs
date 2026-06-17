package org.openwcs.processdesigner.task;

import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Curated task {@code slotting.putaway}: score + dispatch a put-away move for a decanted HU, calling
 * {@code POST /api/slotting/decant/putaway} on slotting (see DecantController.DecantPutawayRequest).
 * Reads {@code warehouseId}, {@code huId}, {@code skuId}, {@code qty} (required) plus optional
 * {@code fromLocationId}. Returns the decant result fields if present.
 */
@Component
public class SlottingPutawayTask implements ProcessTask {

    private final RestClient http;

    public SlottingPutawayTask(RestClient.Builder builder,
                               @Value("${openwcs.process-designer.slotting-base-url:http://localhost:8093}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    @Override
    public String type() {
        return "slotting.putaway";
    }

    @Override
    public TaskSpec spec() {
        return new TaskSpec("slotting.putaway", "Slot + put away",
                "Score the best storage slot for a decanted handling unit and dispatch the put-away "
                        + "move there (e.g. after goods-in decant).",
                java.util.List.of(
                        TaskSpec.req("warehouseId", "The warehouse the put-away runs in"),
                        TaskSpec.req("huId", "The handling unit to put away"),
                        TaskSpec.req("skuId", "The SKU on the handling unit (drives slotting rules)"),
                        TaskSpec.req("qty", "The quantity being put away"),
                        TaskSpec.opt("fromLocationId", "Optional: the source location the handling unit is leaving")),
                java.util.List.of(
                        TaskSpec.out("toLocationId", "The storage location the slotter chose"),
                        TaskSpec.out("moveId", "The id of the dispatched put-away move")));
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> in) {
        Map<String, Object> body = new HashMap<>();
        body.put("warehouseId", TaskInputs.requireUuid(in, "warehouseId").toString());
        body.put("huId", TaskInputs.requireUuid(in, "huId").toString());
        body.put("skuId", TaskInputs.requireUuid(in, "skuId").toString());
        body.put("qty", TaskInputs.decimal(in, "qty"));
        String fromLoc = TaskInputs.string(in, "fromLocationId");
        if (fromLoc != null) {
            body.put("fromLocationId", fromLoc);
        }

        Map<String, Object> response = TaskHttp.postForMap(http, "/api/slotting/decant/putaway", body, type());
        Map<String, Object> out = new HashMap<>();
        if (response.get("toLocationId") != null) {
            out.put("toLocationId", response.get("toLocationId"));
        }
        if (response.get("moveId") != null) {
            out.put("moveId", response.get("moveId"));
        }
        return out;
    }
}
