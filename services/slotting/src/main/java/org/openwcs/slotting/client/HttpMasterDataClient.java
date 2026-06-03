package org.openwcs.slotting.client;

import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** {@link MasterDataClient} backed by the master-data REST API. */
@Component
public class HttpMasterDataClient implements MasterDataClient {

    private final RestClient http;

    public HttpMasterDataClient(RestClient.Builder builder,
                                @Value("${openwcs.slotting.master-data-base-url:http://localhost:8081}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    @Override
    public List<StorageLocation> storageLocations(UUID warehouseId, UUID blockId) {
        LocationPage page = http.get()
                .uri("/api/master-data/locations?warehouseId={w}&blockId={b}&size=1000", warehouseId, blockId)
                .retrieve()
                .body(LocationPage.class);
        if (page == null || page.content() == null) {
            return List.of();
        }
        return page.content();
    }

    private record LocationPage(List<StorageLocation> content) {
    }
}
