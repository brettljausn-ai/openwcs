package org.openwcs.gtp.client;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** {@link FlowInductionClient} backed by flow-orchestrator's induction-queue API (ADR-0007 §3). */
@Component
public class HttpFlowInductionClient implements FlowInductionClient {

    private final RestClient http;

    public HttpFlowInductionClient(RestClient.Builder builder,
                                   @Value("${openwcs.gtp.flow-base-url:http://localhost:8085}") String baseUrl) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(2))
                .withReadTimeout(Duration.ofSeconds(2));
        this.http = builder.baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }

    @Override
    public List<InductionEntry> readQueue(UUID workplaceId) {
        List<InductionEntry> body = http.get()
                .uri(uri -> uri.path("/api/flow/induction/queue").queryParam("workplaceId", workplaceId).build())
                .retrieve()
                .body(new ParameterizedTypeReference<List<InductionEntry>>() {
                });
        return body == null ? List.of() : body;
    }

    @Override
    public InductionEntry getEntry(UUID entryId) {
        return http.get()
                .uri("/api/flow/induction/entries/{id}", entryId)
                .retrieve()
                .body(InductionEntry.class);
    }

    @Override
    public InductionEntry markDone(UUID entryId) {
        return http.post()
                .uri("/api/flow/induction/entries/{id}/done", entryId)
                .retrieve()
                .body(InductionEntry.class);
    }
}
