package org.openwcs.allocation.client;

import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** {@link MasterDataClient} backed by the master-data REST API. */
@Component
public class HttpMasterDataClient implements MasterDataClient {

    private static final ParameterizedTypeReference<List<ShipperDef>> SHIPPER_LIST =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<List<UomDef>> UOM_LIST =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient http;

    public HttpMasterDataClient(RestClient.Builder builder,
                                @Value("${openwcs.allocation.master-data-base-url:http://localhost:8081}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    @Override
    public FulfillmentConfig fulfillmentConfig(UUID warehouseId) {
        try {
            return http.get()
                    .uri("/api/master-data/warehouses/{id}/fulfillment-config", warehouseId)
                    .retrieve()
                    .body(FulfillmentConfig.class);
        } catch (HttpClientErrorException.NotFound e) {
            // No config configured → sensible default: each-pick, app-cubed, no batching.
            return new FulfillmentConfig(List.of("EACH"), "APP", null, false, 1, 12, null);
        }
    }

    @Override
    public List<UUID> pickLocationIds(UUID warehouseId) {
        LocationPage page = http.get()
                .uri("/api/master-data/locations?warehouseId={w}&purpose=PICK&size=500", warehouseId)
                .retrieve()
                .body(LocationPage.class);
        if (page == null || page.content() == null) {
            return List.of();
        }
        return page.content().stream().map(LocationRef::id).toList();
    }

    @Override
    public List<ShipperDef> shippers(UUID warehouseId) {
        return http.get()
                .uri("/api/master-data/shippers?warehouseId={w}", warehouseId)
                .retrieve()
                .body(SHIPPER_LIST);
    }

    @Override
    public List<UomDef> skuUoms(UUID skuId) {
        return http.get()
                .uri("/api/master-data/skus/{id}/uoms", skuId)
                .retrieve()
                .body(UOM_LIST);
    }

    private record LocationPage(List<LocationRef> content) {
    }

    private record LocationRef(UUID id) {
    }
}
