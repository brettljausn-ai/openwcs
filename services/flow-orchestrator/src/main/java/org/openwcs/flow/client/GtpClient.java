package org.openwcs.flow.client;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Calls the gtp service to project a workstation's STOCK/ORDER conveyor interactions (from the
 * automation topology) into the GTP station's nodes, carrying the feeding conveyor distance.
 */
@Component
public class GtpClient {

    private final RestClient http;

    public GtpClient(RestClient.Builder builder, FlowProperties properties) {
        this.http = builder.baseUrl(properties.getGtpBaseUrl()).build();
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
