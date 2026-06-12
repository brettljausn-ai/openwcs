package org.openwcs.gtp.client;

import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** {@link InventoryClient} backed by the inventory service's HU registry. */
@Component
public class HttpInventoryClient implements InventoryClient {

    private static final Logger log = LoggerFactory.getLogger(HttpInventoryClient.class);

    private final RestClient http;

    public HttpInventoryClient(RestClient.Builder builder,
                               @Value("${openwcs.gtp.inventory-base-url:http://localhost:8082}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    @Override
    public Optional<UUID> huTypeOf(UUID huId) {
        try {
            HandlingUnit hu = http.get()
                    .uri("/api/inventory/handling-units/{id}", huId)
                    .retrieve()
                    .body(HandlingUnit.class);
            return Optional.ofNullable(hu).map(HandlingUnit::huTypeId);
        } catch (RuntimeException e) {
            log.debug("could not resolve HU {}: {}", huId, e.toString());
            return Optional.empty();
        }
    }

    /** Subset of an inventory handling unit (only the type link is needed here). */
    private record HandlingUnit(UUID huTypeId) {
    }
}
