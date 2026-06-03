package org.openwcs.integration.manhattan.client;

import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** {@link MasterDataClient} backed by the master-data SKU API (lookup by code). */
@Component
public class HttpMasterDataClient implements MasterDataClient {

    private final RestClient http;

    public HttpMasterDataClient(RestClient.Builder builder,
                                @Value("${openwcs.integration.master-data-base-url:http://localhost:8081}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    @Override
    public UUID skuIdByCode(String code) {
        SkuPage page = http.get()
                .uri(uri -> uri.path("/api/master-data/skus").queryParam("code", code).build())
                .retrieve()
                .body(SkuPage.class);
        if (page == null || page.content() == null || page.content().isEmpty()) {
            return null;
        }
        return UUID.fromString(page.content().get(0).id());
    }

    private record SkuPage(List<SkuRef> content) {
    }

    private record SkuRef(String id, String code) {
    }
}
