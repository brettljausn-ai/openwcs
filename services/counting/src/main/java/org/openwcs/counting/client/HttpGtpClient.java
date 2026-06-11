package org.openwcs.counting.client;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** {@link GtpClient} backed by the GTP service's station + queue API. */
@Component
public class HttpGtpClient implements GtpClient {

    private static final String STOCK_COUNT = "STOCK_COUNT";

    private final RestClient http;

    public HttpGtpClient(RestClient.Builder builder,
                         @Value("${openwcs.counting.gtp-base-url:http://localhost:8094}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    @Override
    public Optional<UUID> findActiveCountingStation(UUID warehouseId) {
        List<Station> stations = http.get()
                .uri("/api/gtp/stations?warehouseId={w}", warehouseId)
                .retrieve()
                .body(new ParameterizedTypeReference<List<Station>>() {
                });
        if (stations == null) {
            return Optional.empty();
        }
        return stations.stream()
                .filter(s -> s.id() != null)
                .filter(s -> "ACTIVE".equals(s.status()))
                .filter(Station::acceptingWorkOrTrue)
                .filter(s -> s.supportedModes() != null && s.supportedModes().contains(STOCK_COUNT))
                .map(Station::id)
                .findFirst();
    }

    @Override
    @Deprecated
    public void enqueue(UUID stationId, EnqueueRequest request) {
        http.post()
                .uri("/api/gtp/stations/{id}/queue", stationId)
                .body(request)
                .retrieve()
                .toBodilessEntity();
    }

    /** Subset of a GTP station view used to select a counting destination. */
    private record Station(UUID id, String status, Boolean acceptingWork, List<String> supportedModes) {

        /** Treat a missing {@code acceptingWork} flag as accepting (older station views omit it). */
        boolean acceptingWorkOrTrue() {
            return acceptingWork == null || acceptingWork;
        }
    }
}
