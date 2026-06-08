package org.openwcs.flow.client;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Calls the gtp service to project a workstation's STOCK/ORDER conveyor interactions (from the
 * automation topology) into the GTP station's nodes, carrying the feeding conveyor distance.
 *
 * <p>Bounded by short connect/read timeouts: this runs as a best-effort side effect of routing
 * projection, so a slow or unreachable gtp must never stall "Generate routing".
 */
@Component
public class GtpClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final RestClient http;

    public GtpClient(RestClient.Builder builder, FlowProperties properties) {
        var factory = ClientHttpRequestFactories.get(
                ClientHttpRequestFactorySettings.DEFAULTS.withConnectTimeout(TIMEOUT).withReadTimeout(TIMEOUT));
        this.http = builder.baseUrl(properties.getGtpBaseUrl()).requestFactory(factory).build();
    }

    public void syncStationNodes(UUID stationId, List<NodeSpec> nodes) {
        http.post()
                .uri("/api/gtp/stations/{id}/nodes/sync", stationId)
                .body(new SyncBody(nodes))
                .retrieve()
                .toBodilessEntity();
    }

    /** A projected STOCK/ORDER node for a station. */
    public record NodeSpec(String role, String code, UUID locationId, String putLightId,
                           BigDecimal inboundDistanceM) {
    }

    private record SyncBody(List<NodeSpec> nodes) {
    }
}
