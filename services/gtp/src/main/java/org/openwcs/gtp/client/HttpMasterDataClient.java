package org.openwcs.gtp.client;

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
                                @Value("${openwcs.gtp.master-data-base-url:http://localhost:8081}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    @Override
    public Optional<String> storageTypeOfLocation(UUID warehouseId, UUID locationId) {
        try {
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
        } catch (RuntimeException e) {
            log.debug("could not resolve storage type for location {}: {}", locationId, e.toString());
            return Optional.empty();
        }
    }

    @Override
    public boolean singleSkuPerCompartmentRule() {
        try {
            StockRules rules = http.get()
                    .uri("/api/master-data/stock-rules")
                    .retrieve()
                    .body(StockRules.class);
            return rules == null || rules.singleSkuPerCompartment();
        } catch (RuntimeException e) {
            log.debug("could not read stock rules, assuming the default (rule ON): {}", e.toString());
            return true; // the rule defaults to ON; fail towards integrity
        }
    }

    @Override
    public Optional<Integer> compartmentsOfHuType(UUID huTypeId) {
        try {
            HuType huType = http.get()
                    .uri("/api/master-data/handling-unit-types/{id}", huTypeId)
                    .retrieve()
                    .body(HuType.class);
            return Optional.ofNullable(huType).map(HuType::compartments);
        } catch (RuntimeException e) {
            log.debug("could not resolve HU type {}: {}", huTypeId, e.toString());
            return Optional.empty();
        }
    }

    /** Subset of a master-data location (only the storage-block link is needed here). */
    private record Location(UUID blockId) {
    }

    /** Subset of the master-data stock rules. */
    private record StockRules(boolean singleSkuPerCompartment) {
    }

    /** Subset of a master-data handling-unit type. */
    private record HuType(Integer compartments) {
    }

    /** Subset of a master-data storage block. */
    private record StorageBlock(String storageType) {
    }
}
