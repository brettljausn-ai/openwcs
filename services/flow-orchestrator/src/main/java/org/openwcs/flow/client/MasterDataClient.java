package org.openwcs.flow.client;

import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Resolves a piece of equipment's / a workplace's <em>operational location</em> in master-data:
 * every conveyor and workplace automatically has a location named after it (lazily created by
 * master-data), so HUs can always be booked to their current physical place — "HUs always need to
 * be booked to the current location, that might be a conveyor".
 *
 * <p>Best-effort and short-timeouted like {@link InventoryClient}: location bookings are a side
 * effect of the transport pipeline, so a slow or unreachable master-data must never stall a
 * dispatch — the caller books {@code null} (inventory maps it to UNKNOWN) and continues.
 */
@Component
public class MasterDataClient {

    private static final Logger log = LoggerFactory.getLogger(MasterDataClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final RestClient http;

    public MasterDataClient(RestClient.Builder builder, FlowProperties properties) {
        var factory = ClientHttpRequestFactories.get(
                ClientHttpRequestFactorySettings.DEFAULTS.withConnectTimeout(TIMEOUT).withReadTimeout(TIMEOUT));
        this.http = builder.baseUrl(properties.getMasterDataBaseUrl()).requestFactory(factory).build();
    }

    /**
     * The operational location for a named conveyor ({@code kind=EQUIPMENT}, name e.g.
     * {@code BIN_CONVEYOR-1}) or workplace ({@code kind=WORKPLACE}, name e.g. {@code PP1}), lazily
     * created by master-data. Returns {@code null} when the lookup fails or nothing resolves —
     * the caller then books the HU to {@code null} (UNKNOWN).
     */
    public UUID operationalLocation(UUID warehouseId, String kind, String name) {
        if (warehouseId == null || name == null || name.isBlank()) {
            return null;
        }
        try {
            Location location = http.get()
                    .uri("/api/master-data/locations/operational?warehouseId={w}&kind={k}&name={n}",
                            warehouseId, kind, name)
                    .retrieve()
                    .body(Location.class);
            return location == null ? null : location.id();
        } catch (RestClientException e) {
            log.warn("operational-location lookup failed for {} '{}' in warehouse {} (booking will "
                    + "fall back to null/UNKNOWN): {}", kind, name, warehouseId, e.toString());
            return null;
        }
    }

    /** Subset of the master-data location we need: its id. */
    private record Location(UUID id, String code) {
    }
}
