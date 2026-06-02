package org.openwcs.flow.client;

import java.util.HashMap;
import java.util.Map;
import org.openwcs.flow.domain.DeviceTask;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Calls the equipment adapter's {@code POST /tasks} over HTTP, resolving the adapter base URL
 * by the task's family. Synchronous (simulator-friendly); see {@link DeviceClient} for the
 * production async contract.
 */
@Component
public class HttpDeviceClient implements DeviceClient {

    private final RestClient.Builder builder;
    private final FlowProperties properties;

    public HttpDeviceClient(RestClient.Builder builder, FlowProperties properties) {
        this.builder = builder;
        this.properties = properties;
    }

    @Override
    public DeviceResult execute(DeviceTask task) {
        String baseUrl = properties.getAdapters().get(task.getFamily());
        if (baseUrl == null) {
            throw new NoAdapterException(task.getFamily());
        }
        Map<String, Object> body = new HashMap<>();
        body.put("taskId", task.getId());
        body.put("warehouseId", task.getWarehouseId());
        body.put("equipmentId", task.getEquipmentId());
        body.put("command", task.getCommand());
        body.put("payload", task.getPayload());

        return builder.baseUrl(baseUrl).build()
                .post()
                .uri("/tasks")
                .body(body)
                .retrieve()
                .body(DeviceResult.class);
    }
}
