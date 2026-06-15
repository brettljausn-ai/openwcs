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

    /**
     * The storage block ({@code block_id}, the slotting pool) a location belongs to, used to decide a
     * move's transport: same block ⇒ same storage system ⇒ a single in-aisle RELOCATE; different
     * blocks ⇒ cross-system ⇒ a RETRIEVE → CONVEY → STORE chain. Returns {@code null} when the
     * location has no block (an unslotted topology / operational location such as a conveyor or
     * workplace) OR when the lookup fails — callers treat "no block resolved on one side" as a
     * cross-system signal (a storage slot → a conveyor/pick-face on a different family).
     */
    public UUID blockId(UUID warehouseId, UUID locationId) {
        if (locationId == null) {
            return null;
        }
        try {
            FullLocation location = http.get()
                    .uri("/api/master-data/locations/{id}", locationId)
                    .retrieve()
                    .body(FullLocation.class);
            return location == null ? null : location.blockId();
        } catch (RestClientException e) {
            log.warn("location block lookup failed for {} in warehouse {} (treating as unresolved): {}",
                    locationId, warehouseId, e.toString());
            return null;
        }
    }

    /** Subset of the master-data location carrying its storage block. */
    private record FullLocation(UUID id, UUID blockId) {
    }

    /** Whether master-data demo mode (DEMO_MODE_ENABLED) is currently ON; false on any error. */
    public boolean demoEnabled() {
        try {
            DemoStatus status = http.get()
                    .uri("/api/master-data/demo")
                    .retrieve()
                    .body(DemoStatus.class);
            return status != null && status.enabled();
        } catch (RestClientException e) {
            log.warn("could not read the demo flag from master-data (treating as off): {}", e.toString());
            return false;
        }
    }

    /** Subset of the master-data demo-status response. */
    private record DemoStatus(boolean enabled) {
    }
}
