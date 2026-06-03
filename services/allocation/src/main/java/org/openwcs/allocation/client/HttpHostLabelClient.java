package org.openwcs.allocation.client;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * {@link HostLabelClient} backed by the host integration gateway (e.g. integration-sap). Active
 * when {@code openwcs.allocation.host-label-base-url} is set; otherwise the simulator is used.
 * POSTs one request per shipper to obtain that carton's label barcode. Identity is forwarded by
 * the shared {@code RestClientCustomizer}.
 */
@Component
@ConditionalOnProperty(name = "openwcs.allocation.host-label-base-url")
public class HttpHostLabelClient implements HostLabelClient {

    private final RestClient http;

    public HttpHostLabelClient(RestClient.Builder builder,
                               @Value("${openwcs.allocation.host-label-base-url}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    @Override
    public String requestBarcode(String orderRef, UUID warehouseId, String serviceCode, String routeCode, int seqNo) {
        Map<String, Object> body = new HashMap<>();
        body.put("orderRef", orderRef);
        body.put("warehouseId", warehouseId);
        body.put("serviceCode", serviceCode);
        body.put("routeCode", routeCode);
        body.put("seqNo", seqNo);
        LabelBarcodeResponse response = http.post()
                .uri("/api/integration/sap/labels")
                .body(body)
                .retrieve()
                .body(LabelBarcodeResponse.class);
        return response == null ? null : response.barcode();
    }

    private record LabelBarcodeResponse(String barcode) {
    }
}
