package org.openwcs.process.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** {@link AllocationClient} backed by the allocation service's allocate + cube API. */
@Component
public class HttpAllocationClient implements AllocationClient {

    private final RestClient http;

    public HttpAllocationClient(RestClient.Builder builder,
                                @Value("${openwcs.process.allocation-base-url:http://localhost:8091}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    @Override
    public Allocation allocate(String orderRef, UUID warehouseId, List<Line> lines) {
        List<Map<String, Object>> lineBodies = new ArrayList<>();
        for (Line line : lines) {
            Map<String, Object> lineBody = new HashMap<>();
            lineBody.put("lineNo", line.lineNo());
            lineBody.put("skuId", line.skuId());
            lineBody.put("qty", line.qty());
            lineBodies.add(lineBody);
        }
        Map<String, Object> body = new HashMap<>();
        body.put("orderRef", orderRef);
        body.put("warehouseId", warehouseId);
        body.put("lines", lineBodies);

        AllocationResponse response = http.post().uri("/api/allocation/orders").body(body)
                .retrieve().body(AllocationResponse.class);
        if (response == null) {
            return new Allocation(orderRef, "NOT_FULFILLABLE", 0);
        }
        int shipperCount = response.shippers() == null ? 0 : response.shippers().size();
        return new Allocation(response.orderRef(), response.status(), shipperCount);
    }

    /** Subset of the allocation service's {@code AllocationView} the process engine needs. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record AllocationResponse(String orderRef, String status, List<Object> shippers) {
    }
}
