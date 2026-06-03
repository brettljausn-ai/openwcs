package org.openwcs.process.client;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** {@link SlottingClient} backed by the slotting service's put-away API. */
@Component
public class HttpSlottingClient implements SlottingClient {

    private final RestClient http;

    public HttpSlottingClient(RestClient.Builder builder,
                              @Value("${openwcs.process.slotting-base-url:http://localhost:8093}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    @Override
    public Putaway assignPutaway(UUID warehouseId, UUID huId, UUID skuId, UUID batchId, UUID uomId,
                                 Object qty, UUID blockId) {
        Map<String, Object> body = new HashMap<>();
        body.put("warehouseId", warehouseId);
        body.put("huId", huId);
        body.put("skuId", skuId);
        body.put("batchId", batchId);
        body.put("uomId", uomId);
        body.put("qty", qty);
        body.put("blockId", blockId);
        return http.post().uri("/api/slotting/putaway").body(body).retrieve().body(Putaway.class);
    }
}
