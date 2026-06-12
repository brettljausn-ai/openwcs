package org.openwcs.inventory.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

/** {@link MasterDataClient} backed by the master-data service's REST API. */
@Component
public class HttpMasterDataClient implements MasterDataClient {

    private static final Logger log = LoggerFactory.getLogger(HttpMasterDataClient.class);

    /** Page size when walking master-data's paged listings (warehouses / locations). */
    private static final int PAGE_SIZE = 500;

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

    @Override
    public List<UUID> warehouseIds() {
        return pageThrough("warehouses",
                page -> uri -> uri.path("/api/master-data/warehouses")
                        .queryParam("page", page)
                        .queryParam("size", PAGE_SIZE)
                        .build());
    }

    @Override
    public List<UUID> storageBlockIds(UUID warehouseId) {
        try {
            List<IdOnly> blocks = http.get()
                    .uri(uri -> uri.path("/api/master-data/storage-blocks")
                            .queryParam("warehouseId", warehouseId)
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<IdOnly>>() { });
            return blocks == null ? List.of() : blocks.stream().map(IdOnly::id).toList();
        } catch (RestClientException e) {
            throw unavailable("storage blocks of warehouse " + warehouseId, e);
        }
    }

    @Override
    public List<UUID> blockLocationIds(UUID warehouseId, UUID blockId) {
        return pageThrough("locations of block " + blockId,
                page -> uri -> uri.path("/api/master-data/locations")
                        .queryParam("warehouseId", warehouseId)
                        .queryParam("blockId", blockId)
                        .queryParam("page", page)
                        .queryParam("size", PAGE_SIZE)
                        .build());
    }

    /** Walks a master-data paged listing and collects the row ids. */
    private List<UUID> pageThrough(String what, Function<Integer, Function<UriBuilder, java.net.URI>> uriForPage) {
        List<UUID> ids = new ArrayList<>();
        int page = 0;
        int totalPages = 1;
        try {
            while (page < totalPages) {
                int current = page;
                IdPage result = http.get()
                        .uri(uri -> uriForPage.apply(current).apply(uri))
                        .retrieve()
                        .body(IdPage.class);
                if (result == null || result.content() == null) {
                    break;
                }
                result.content().forEach(row -> ids.add(row.id()));
                totalPages = result.totalPages();
                page++;
            }
        } catch (RestClientException e) {
            throw unavailable(what, e);
        }
        return ids;
    }

    private MasterDataUnavailableException unavailable(String what, RestClientException e) {
        log.warn("master-data lookup failed: {} could not be listed ({});"
                + " the depending operation is rejected with 503", what, e.toString());
        return new MasterDataUnavailableException(
                "Master-data is unreachable; " + what + " cannot be listed.", e);
    }

    /** Subset of a master-data location (id + code are all the inventory service needs). */
    private record Location(UUID id, String code) {
    }

    /** Any master-data row of which only the id matters here (warehouse / block / location). */
    private record IdOnly(UUID id) {
    }

    /** Subset of master-data's page envelope (content rows + page count). */
    private record IdPage(List<IdOnly> content, int totalPages) {
    }
}
