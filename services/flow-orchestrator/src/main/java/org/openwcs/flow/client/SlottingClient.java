package org.openwcs.flow.client;

import java.time.Duration;
import java.util.List;
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
 * <p>Bounded by short connect/read timeouts and isolated by the caller (like
 * {@link InventoryClient}): a slow or unreachable slotting must never stall the induction
 * pipeline — flow falls back to today's direct RETRIEVE.
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

    /** Steps are ordered front-most first; empty + {@code blocked=true} = blocked but unplannable. */
    public record RelocationPlan(List<RelocationStep> steps, boolean blocked) {
    }

    /** One blocker move: take {@code huId} out of {@code fromLocationId} into {@code toLocationId}. */
    public record RelocationStep(UUID huId, String huCode, UUID fromLocationId, UUID toLocationId) {
    }

    private record PlanRequest(UUID warehouseId, UUID locationId) {
    }
}
