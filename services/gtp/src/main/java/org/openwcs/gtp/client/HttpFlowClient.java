package org.openwcs.gtp.client;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** {@link FlowClient} backed by flow-orchestrator's device-task API. */
@Component
public class HttpFlowClient implements FlowClient {

    private final RestClient http;

    public HttpFlowClient(RestClient.Builder builder,
                          @Value("${openwcs.gtp.flow-base-url:http://localhost:8085}") String baseUrl) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(2))
                .withReadTimeout(Duration.ofSeconds(2));
        this.http = builder.baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }

    @Override
    public UUID createTransport(UUID warehouseId, String family, String command,
                                Map<String, Object> payload, UUID correlationId) {
        Map<String, Object> body = new HashMap<>();
        body.put("warehouseId", warehouseId);
        body.put("family", family);
        body.put("command", command);
        body.put("payload", payload);
        body.put("correlationId", correlationId);

        DeviceTask task = http.post()
                .uri("/api/flow/device-tasks")
                .body(body)
                .retrieve()
                .body(DeviceTask.class);
        return task == null ? null : task.id();
    }

    /** Subset of the flow device-task response. */
    private record DeviceTask(UUID id) {
    }
}
