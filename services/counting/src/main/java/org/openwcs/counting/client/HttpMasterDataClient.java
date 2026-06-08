package org.openwcs.counting.client;

import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** {@link MasterDataClient} backed by the master-data service. */
@Component
public class HttpMasterDataClient implements MasterDataClient {

    private static final Logger log = LoggerFactory.getLogger(HttpMasterDataClient.class);

    private final RestClient http;

    public HttpMasterDataClient(RestClient.Builder builder,
                                @Value("${openwcs.counting.master-data-base-url:http://localhost:8081}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    @Override
    public boolean emulatorEnabled() {
        EmulatorStatus status = http.get()
                .uri("/api/master-data/emulator")
                .retrieve()
                .body(EmulatorStatus.class);
        return status != null && status.enabled();
    }

    @Override
    public Optional<String> storageTypeOfLocation(UUID warehouseId, UUID locationId) {
        Location location = http.get()
                .uri("/api/master-data/locations/{id}", locationId)
                .retrieve()
                .body(Location.class);
        if (location == null || location.blockId() == null) {
            return Optional.empty();
        }
        StorageBlock block = http.get()
                .uri("/api/master-data/storage-blocks/{id}", location.blockId())
                .retrieve()
                .body(StorageBlock.class);
        return Optional.ofNullable(block).map(StorageBlock::storageType);
    }

    @Override
    public Optional<String> skuCode(UUID skuId) {
        try {
            Sku sku = http.get()
                    .uri("/api/master-data/skus/{id}", skuId)
                    .retrieve()
                    .body(Sku.class);
            return Optional.ofNullable(sku).map(Sku::code);
        } catch (RuntimeException e) {
            log.debug("could not resolve SKU code for {}: {}", skuId, e.toString());
            return Optional.empty();
        }
    }

    /** Subset of the master-data emulator-status response. */
    private record EmulatorStatus(boolean enabled) {
    }

    /** Subset of a master-data location (only the storage-block link is needed here). */
    private record Location(UUID blockId) {
    }

    /** Subset of a master-data storage block. */
    private record StorageBlock(String storageType) {
    }

    /** Subset of a master-data SKU. */
    private record Sku(String code) {
    }
}
