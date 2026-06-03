package org.openwcs.orders.client;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** {@link MasterDataClient} backed by the master-data catalog REST API (lookup by code). */
@Component
public class HttpMasterDataClient implements MasterDataClient {

    private final RestClient http;

    public HttpMasterDataClient(RestClient.Builder builder,
                                @Value("${openwcs.orders.master-data-base-url:http://localhost:8081}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    @Override
    public boolean shippingServiceExists(String code) {
        return activeExists("/api/master-data/shipping-services", code);
    }

    @Override
    public boolean routeExists(String code) {
        return activeExists("/api/master-data/routes", code);
    }

    @Override
    public boolean labelTemplateExists(String code) {
        return activeExists("/api/master-data/label-templates", code);
    }

    private boolean activeExists(String path, String code) {
        return services(path, code).stream()
                .anyMatch(e -> code.equals(e.code()) && !"ARCHIVED".equals(e.status()));
    }

    @Override
    public String serviceLabelTemplate(String serviceCode) {
        return services("/api/master-data/shipping-services", serviceCode).stream()
                .filter(e -> serviceCode.equals(e.code()) && !"ARCHIVED".equals(e.status()))
                .map(CatalogEntry::labelTemplateCode)
                .findFirst()
                .orElse(null);
    }

    @Override
    public String warehouseDefaultLabelTemplate(java.util.UUID warehouseId) {
        try {
            ConfigEntry config = http.get()
                    .uri("/api/master-data/warehouses/{id}/fulfillment-config", warehouseId)
                    .retrieve()
                    .body(ConfigEntry.class);
            return config == null ? null : config.defaultLabelTemplateCode();
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            return null; // no fulfillment config for this warehouse
        }
    }

    private List<CatalogEntry> services(String path, String code) {
        List<CatalogEntry> hits = http.get()
                .uri(uri -> uri.path(path).queryParam("code", code).build())
                .retrieve()
                .body(new ParameterizedTypeReference<List<CatalogEntry>>() {
                });
        return hits == null ? List.of() : hits;
    }

    private record CatalogEntry(String code, String status, String labelTemplateCode) {
    }

    private record ConfigEntry(String defaultLabelTemplateCode) {
    }
}
