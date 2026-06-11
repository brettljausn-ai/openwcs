package org.openwcs.flow.client;

import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Books a handling unit's registry location in the inventory service through the transport
 * lifecycle: a RETRIEVE out of a slot books {@code null} (in transit / at a workplace — the HU
 * transport trace is the truth while away); the return-leg STORE books the source slot back.
 *
 * <p>Bounded by short connect/read timeouts: callers run this as a best-effort side effect of the
 * induction pipeline and isolate failures, so a slow or unreachable inventory must never stall a
 * transport callback.
 */
@Component
public class InventoryClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final RestClient http;

    public InventoryClient(RestClient.Builder builder, FlowProperties properties) {
        var factory = ClientHttpRequestFactories.get(
                ClientHttpRequestFactorySettings.DEFAULTS.withConnectTimeout(TIMEOUT).withReadTimeout(TIMEOUT));
        this.http = builder.baseUrl(properties.getInventoryBaseUrl()).requestFactory(factory).build();
    }

    /** Set the HU's current location; {@code locationId = null} books it out of its slot. */
    public void bookLocation(UUID huId, UUID locationId) {
        http.put()
                .uri("/api/inventory/handling-units/{id}/location", huId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new LocationBody(locationId))
                .retrieve()
                .toBodilessEntity();
    }

    private record LocationBody(UUID locationId) {
    }
}
