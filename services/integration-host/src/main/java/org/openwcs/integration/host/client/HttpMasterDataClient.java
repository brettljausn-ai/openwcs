package org.openwcs.integration.host.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** {@link MasterDataClient} backed by the master-data SKU API (upsert by code). */
@Component
public class HttpMasterDataClient implements MasterDataClient {

    private final RestClient http;

    public HttpMasterDataClient(RestClient.Builder builder,
                                @Value("${openwcs.host.master-data-base-url:http://localhost:8081}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    @Override
    public UpsertResult upsertSku(SkuDto sku) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", sku.code());
        body.put("description", sku.description());
        body.put("ownerClient", sku.ownerClient());
        body.put("batchTracked", sku.batchTracked());
        body.put("serialTracked", sku.serialTracked());
        body.put("dateTracked", sku.dateTracked());

        String existingId = findIdByCode(sku.code());
        if (existingId != null) {
            http.put().uri("/api/master-data/skus/{id}", existingId).body(body).retrieve().toBodilessEntity();
            return UpsertResult.UPDATED;
        }
        http.post().uri("/api/master-data/skus").body(body).retrieve().toBodilessEntity();
        return UpsertResult.CREATED;
    }

    private String findIdByCode(String code) {
        SkuPage page = http.get()
                .uri(uri -> uri.path("/api/master-data/skus").queryParam("code", code).build())
                .retrieve()
                .body(SkuPage.class);
        if (page == null || page.content() == null || page.content().isEmpty()) {
            return null;
        }
        return page.content().get(0).id();
    }

    private record SkuPage(List<SkuRef> content) {
    }

    private record SkuRef(String id, String code) {
    }
}
