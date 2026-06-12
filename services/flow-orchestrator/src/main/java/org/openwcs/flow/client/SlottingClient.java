package org.openwcs.flow.client;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Asks the slotting service for a channel's relocation plan (ADR-0009 dig-out): the ordered
 * blocker moves (front-most first) that must happen before an HU deeper in a multi-deep channel
 * can be retrieved. An empty plan with {@code blocked=false} means the channel is clear; empty
 * with {@code blocked=true} means blocked but unplannable (flow degrades to a direct retrieve).
 *
 * <p>Also asks slotting where a tote goes after its workplace visit ({@link #bestLocation}):
 * ONLY slotting is allowed to slot a tote — the return leg never falls back to the source slot.
 *
 * <p>Bounded by short connect/read timeouts and isolated by the caller (like
 * {@link InventoryClient}): a slow or unreachable slotting must never stall the induction
 * pipeline — flow falls back to today's direct RETRIEVE (dig-out) or leaves the tote on the
 * conveyor awaiting a slot (return leg).
 */
@Component
public class SlottingClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final RestClient http;

    public SlottingClient(RestClient.Builder builder, FlowProperties properties) {
        var factory = ClientHttpRequestFactories.get(
                ClientHttpRequestFactorySettings.DEFAULTS.withConnectTimeout(TIMEOUT).withReadTimeout(TIMEOUT));
        this.http = builder.baseUrl(properties.getSlottingBaseUrl()).requestFactory(factory).build();
    }

    /** The dig-out plan for the channel holding {@code locationId} (the retrieve's source slot). */
    public RelocationPlan plan(UUID warehouseId, UUID locationId) {
        return http.post()
                .uri("/api/slotting/relocation-plan")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PlanRequest(warehouseId, locationId))
                .retrieve()
                .body(RelocationPlan.class);
    }

    /**
     * The currently-best storage location for a handling unit returning from a workplace (the
     * scored put-away choice), or empty when slotting has no place for it. Mirrors the put-away
     * contract gtp used to call before the return decision moved here (POST /api/slotting/putaway).
     */
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
        // blockId omitted: slotting resolves the storage block from the SKU's storage profile (or
        // the warehouse's only automated block) and returns the best empty, unreserved location.
        PutawayDecision decision = http.post()
                .uri("/api/slotting/putaway")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(PutawayDecision.class);
        return decision == null ? Optional.empty() : Optional.ofNullable(decision.locationId());
    }

    /**
     * Confirms to slotting that the HU physically arrived in storage, closing its open put-away
     * assignment (the active-ledger row that counts as planned occupancy). Idempotent.
     */
    public void confirmStored(UUID warehouseId, UUID huId) {
        http.post()
                .uri("/api/slotting/putaway/stored")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("warehouseId", warehouseId, "huId", huId))
                .retrieve()
                .toBodilessEntity();
    }

    /** Subset of the put-away decision we need: the chosen location. */
    private record PutawayDecision(UUID locationId) {
    }

    /** Steps are ordered front-most first; empty + {@code blocked=true} = blocked but unplannable. */
    public record RelocationPlan(List<RelocationStep> steps, boolean blocked) {
    }

    /** One blocker move: take {@code huId} out of {@code fromLocationId} into {@code toLocationId}. */
    public record RelocationStep(UUID huId, String huCode, UUID fromLocationId, UUID toLocationId) {
    }

    private record PlanRequest(UUID warehouseId, UUID locationId) {
    }
}
