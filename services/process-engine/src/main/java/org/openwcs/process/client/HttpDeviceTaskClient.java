package org.openwcs.process.client;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** {@link DeviceTaskClient} backed by the flow-orchestrator device-task API. */
@Component
public class HttpDeviceTaskClient implements DeviceTaskClient {

    private final RestClient http;

    public HttpDeviceTaskClient(RestClient.Builder builder,
                                @Value("${openwcs.process.flow-orchestrator-base-url:http://localhost:8085}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    @Override
    public void dispatch(UUID warehouseId, String family, UUID equipmentId, String command,
                         Map<String, Object> payload, UUID correlationId) {
        Map<String, Object> body = new HashMap<>();
        body.put("warehouseId", warehouseId);
        body.put("family", family);
        body.put("equipmentId", equipmentId);
        body.put("command", command);
        body.put("payload", payload);
        body.put("correlationId", correlationId);
        http.post().uri("/api/flow/device-tasks").body(body).retrieve().toBodilessEntity();
    }
}
