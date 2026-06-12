package org.openwcs.inventory.client;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** {@link MasterDataClient} backed by the master-data service's operational-location endpoint. */
@Component
public class HttpMasterDataClient implements MasterDataClient {

    private static final Logger log = LoggerFactory.getLogger(HttpMasterDataClient.class);

    private final RestClient http;

    /** Warehouse id to UNKNOWN-location id; the id never changes, so a process-local cache is safe. */
    private final Map<UUID, UUID> unknownLocationByWarehouse = new ConcurrentHashMap<>();

    public HttpMasterDataClient(
            RestClient.Builder builder,
            @Value("${openwcs.inventory.master-data-base-url:http://localhost:8081}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    @Override
    public UUID unknownLocationId(UUID warehouseId) {
        UUID cached = unknownLocationByWarehouse.get(warehouseId);
        if (cached != null) {
            return cached;
        }
        Location location;
        try {
            location = http.get()
                    .uri(uri -> uri.path("/api/master-data/locations/operational")
                            .queryParam("warehouseId", warehouseId)
                            .queryParam("kind", "UNKNOWN")
                            .build())
                    .retrieve()
                    .body(Location.class);
        } catch (RestClientException e) {
            log.warn("UNKNOWN location unresolved: master-data is unreachable for warehouse {} ({});"
                    + " the depending operation is rejected with 503", warehouseId, e.toString());
            throw new MasterDataUnavailableException(
                    "Master-data is unreachable; the UNKNOWN location of warehouse " + warehouseId
                            + " cannot be resolved.", e);
        }
        if (location == null || location.id() == null) {
            log.warn("UNKNOWN location unresolved: master-data returned no location for warehouse {};"
                    + " the depending operation is rejected with 503", warehouseId);
            throw new MasterDataUnavailableException(
                    "Master-data returned no UNKNOWN location for warehouse " + warehouseId + ".", null);
        }
        unknownLocationByWarehouse.put(warehouseId, location.id());
        log.info("UNKNOWN location resolved: warehouse {} books position-less HUs to location {} ({})",
                warehouseId, location.code(), location.id());
        return location.id();
    }

    /** Subset of a master-data location (id + code are all the inventory service needs). */
    private record Location(UUID id, String code) {
    }
}
