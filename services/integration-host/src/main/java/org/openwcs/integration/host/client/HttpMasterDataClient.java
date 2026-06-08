package org.openwcs.integration.host.client;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * {@link MasterDataClient} backed by the master-data SKU sync API. The DTO field names match the
 * {@code SkuSyncRequest} contract, so the batch is forwarded as-is and master-data performs the
 * upsert-by-code plus full reconcile of UoMs and barcodes in one transaction. This is a direct
 * service-to-service call (no gateway), so it carries no identity header and is allowed to write
 * host-owned master data (see master-data {@code HostManagedGuard}).
 */
@Component
public class HttpMasterDataClient implements MasterDataClient {

    private final RestClient http;

    public HttpMasterDataClient(RestClient.Builder builder,
                                @Value("${openwcs.host.master-data-base-url:http://localhost:8081}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    @Override
    public SyncReport syncSkus(List<SkuDto> skus) {
        return http.post()
                .uri("/api/master-data/skus/sync")
                .body(skus)
                .retrieve()
                .body(SyncReport.class);
    }
}
