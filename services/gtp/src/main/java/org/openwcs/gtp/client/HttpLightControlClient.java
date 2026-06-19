package org.openwcs.gtp.client;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * {@link LightControlClient} backed by the PTL adapter's (or the equipment emulator's) uniform
 * device-task contract: {@code POST {base}/tasks} with a {@code family:"PTL"} task carrying an
 * {@code ILLUMINATE} or {@code CLEAR} command. The base URL is resolved like flow-orchestrator's
 * dispatch: the emulator when hardware-emulator mode is on, otherwise the real PTL adapter. Every
 * call is best-effort: any failure is logged at WARN and swallowed so the pick cycle is never
 * blocked. Bounded by short timeouts so a slow/absent device cannot stall a present/confirm/close.
 */
@Component
public class HttpLightControlClient implements LightControlClient {

    private static final Logger log = LoggerFactory.getLogger(HttpLightControlClient.class);

    private final RestClient.Builder builder;
    private final EmulatorModeClient emulatorMode;
    private final String ptlBaseUrl;
    private final String emulatorUrl;

    public HttpLightControlClient(RestClient.Builder builder,
                                  EmulatorModeClient emulatorMode,
                                  @Value("${openwcs.gtp.ptl-base-url:http://localhost:9098}") String ptlBaseUrl,
                                  @Value("${openwcs.gtp.emulator-url:http://localhost:9097}") String emulatorUrl) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(2))
                .withReadTimeout(Duration.ofSeconds(2));
        this.builder = builder.requestFactory(ClientHttpRequestFactories.get(settings));
        this.emulatorMode = emulatorMode;
        this.ptlBaseUrl = ptlBaseUrl;
        this.emulatorUrl = emulatorUrl;
    }

    @Override
    public void illuminate(UUID warehouseId, String equipmentId, String lightId, BigDecimal qty) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("lightId", lightId);
        payload.put("qty", qty);
        payload.put("color", "green");
        send(warehouseId, equipmentId, "ILLUMINATE", payload, lightId);
    }

    @Override
    public void clear(UUID warehouseId, String equipmentId, String lightId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("lightId", lightId);
        send(warehouseId, equipmentId, "CLEAR", payload, lightId);
    }

    /** Best-effort POST of one PTL device task; never throws. */
    private void send(UUID warehouseId, String equipmentId, String command,
                      Map<String, Object> payload, String lightId) {
        boolean emulatorOn = emulatorMode.enabled();
        String baseUrl = emulatorOn ? emulatorUrl : ptlBaseUrl;
        Map<String, Object> body = new HashMap<>();
        body.put("taskId", UUID.randomUUID());
        body.put("warehouseId", warehouseId);
        body.put("equipmentId", equipmentId);
        body.put("family", "PTL");
        body.put("command", command);
        body.put("payload", payload);
        try {
            builder.baseUrl(baseUrl).build()
                    .post()
                    .uri("/tasks")
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.debug("PTL {} sent to {} at {}: equipment {} light {}",
                    command, emulatorOn ? "the equipment emulator" : "the PTL adapter",
                    baseUrl, equipmentId, lightId);
        } catch (RuntimeException e) {
            log.warn("PTL {} for equipment {} light {} failed (best-effort, pick continues): {}",
                    command, equipmentId, lightId, e.toString());
        }
    }
}
