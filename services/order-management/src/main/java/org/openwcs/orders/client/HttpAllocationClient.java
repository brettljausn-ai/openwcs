package org.openwcs.orders.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** {@link AllocationClient} backed by the allocation service REST API. */
@Component
public class HttpAllocationClient implements AllocationClient {

    private final RestClient http;

    public HttpAllocationClient(RestClient.Builder builder,
                                @Value("${openwcs.orders.allocation-base-url:http://localhost:8091}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    @Override
    public AllocationResult allocate(String orderRef, UUID warehouseId, List<Line> lines, Dispatch dispatch,
                                     boolean allowShort) {
        List<Map<String, Object>> lineBodies = new ArrayList<>();
        for (Line line : lines) {
            lineBodies.add(Map.of("lineNo", line.lineNo(), "skuId", line.skuId(), "qty", line.qty()));
        }
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("orderRef", orderRef);
        body.put("warehouseId", warehouseId);
        body.put("lines", lineBodies);
        if (allowShort) {
            body.put("allowShort", true);
        }
        if (dispatch != null) {
            Map<String, Object> dispatchBody = new java.util.HashMap<>();
            dispatchBody.put("shipTo", dispatch.shipTo()); // serialized by field name; matches allocation ShipTo
            dispatchBody.put("serviceCode", dispatch.serviceCode());
            dispatchBody.put("routeCode", dispatch.routeCode());
            dispatchBody.put("labelTemplateCode", dispatch.labelTemplateCode());
            body.put("dispatch", dispatchBody);
        }
        AllocationResponse response = http.post()
                .uri("/api/allocation/orders")
                .body(body)
                .retrieve()
                .body(AllocationResponse.class);
        if (response == null) {
            return new AllocationResult("NOT_FULFILLABLE", null, List.of());
        }
        List<LineResult> lineResults = response.lines() == null ? List.of()
                : response.lines().stream()
                        .map(l -> new LineResult(l.lineNo(), l.allocatedQty(), l.status()))
                        .toList();
        return new AllocationResult(response.status(), response.statusDetail(), lineResults);
    }

    @Override
    public void cancel(String orderRef) {
        try {
            http.post()
                    .uri("/api/allocation/orders/{ref}/cancel", orderRef)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.NotFound e) {
            // No allocation for this order (e.g. it was never released) — nothing to cancel.
        }
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record AllocationResponse(String status, String statusDetail, List<LineResponse> lines) {
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record LineResponse(int lineNo, java.math.BigDecimal allocatedQty, String status) {
    }
}
