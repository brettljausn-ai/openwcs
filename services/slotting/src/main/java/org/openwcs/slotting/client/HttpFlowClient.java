package org.openwcs.slotting.client;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * {@link FlowClient} backed by flow-orchestrator's move API
 * ({@code POST /api/flow/moves}). Bounded by short connect/read timeouts so a slow or unreachable
 * flow surfaces quickly as a {@link FlowDispatchException} and leaves the slotting plan untouched.
 */
@Component
public class HttpFlowClient implements FlowClient {

    private static final Logger log = LoggerFactory.getLogger(HttpFlowClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final RestClient http;

    public HttpFlowClient(RestClient.Builder builder,
                          @Value("${openwcs.slotting.flow-base-url:http://localhost:8085}") String baseUrl) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(TIMEOUT)
                .withReadTimeout(TIMEOUT);
        this.http = builder.baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }

    @Override
    public UUID dispatchMove(UUID warehouseId, UUID huId, String huCode, UUID fromLocationId,
                             UUID toLocationId, String family, String reason) {
        Map<String, Object> body = new HashMap<>();
        body.put("warehouseId", warehouseId);
        body.put("huId", huId);
        body.put("huCode", huCode);
        body.put("fromLocationId", fromLocationId);
        body.put("toLocationId", toLocationId);
        body.put("family", family);
        body.put("reason", reason);
        try {
            MoveResult result = http.post()
                    .uri("/api/flow/moves")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(MoveResult.class);
            if (result == null || result.deviceTaskId() == null) {
                throw new FlowDispatchException("flow returned no deviceTaskId for hu " + huId);
            }
            return result.deviceTaskId();
        } catch (FlowDispatchException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("flow move dispatch failed for hu {} ({} -> {}): {}",
                    huId, fromLocationId, toLocationId, e.toString());
            throw new FlowDispatchException("flow move dispatch failed for hu " + huId, e);
        }
    }

    /** Subset of the flow move response: the created device-task id. */
    private record MoveResult(UUID deviceTaskId) {
    }
}
