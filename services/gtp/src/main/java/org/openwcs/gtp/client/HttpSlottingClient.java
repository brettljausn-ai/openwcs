package org.openwcs.gtp.client;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** {@link SlottingClient} backed by the slotting service's put-away API. */
@Component
public class HttpSlottingClient implements SlottingClient {

    private final RestClient http;

    public HttpSlottingClient(RestClient.Builder builder,
                              @Value("${openwcs.gtp.slotting-base-url:http://localhost:8093}") String baseUrl) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(2))
                .withReadTimeout(Duration.ofSeconds(2));
        this.http = builder.baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }

    @Override
    public Optional<UUID> bestLocation(UUID warehouseId, UUID huId, UUID skuId, BigDecimal qty) {
        if (skuId == null) {
            return Optional.empty(); // slotting needs a SKU to score a location.
        }
        Map<String, Object> body = new HashMap<>();
        body.put("warehouseId", warehouseId);
        body.put("huId", huId);
        body.put("skuId", skuId);
        body.put("qty", qty);
        body.put("empty", false);
        // blockId omitted: slotting resolves the storage block from the SKU's storage profile and
        // returns the currently-best empty, unreserved location within it.
        Decision decision = http.post()
                .uri("/api/slotting/putaway")
                .body(body)
                .retrieve()
                .body(Decision.class);
        return decision == null ? Optional.empty() : Optional.ofNullable(decision.locationId());
    }

    /** Subset of the put-away decision we need: the chosen location. */
    private record Decision(UUID locationId) {
    }
}
