package org.openwcs.gtp.client;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * {@link TxLogClient} backed by the txlog service's append API ({@code POST /api/txlog/events}).
 * Emits a {@code StockAdjusted} event keyed on the warehouse+SKU stream; the inventory projection
 * applies the {@code qtyDelta} to the bucket (see inventory {@code StockMovementPayloads.Adjust}).
 */
@Component
public class HttpTxLogClient implements TxLogClient {

    static final String EVENT_TYPE = "StockAdjusted";

    private final RestClient http;

    public HttpTxLogClient(RestClient.Builder builder,
                           @Value("${openwcs.gtp.txlog-base-url:http://localhost:8086}") String baseUrl) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(2))
                .withReadTimeout(Duration.ofSeconds(2));
        this.http = builder.baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }

    @Override
    public UUID postStockAdjusted(StockAdjustment a) {
        // Inventory's Adjust payload: warehouseId, skuId, batchId, locationId, status, qtyDelta, uomCode.
        Map<String, Object> payload = new HashMap<>();
        payload.put("warehouseId", a.warehouseId());
        payload.put("skuId", a.skuId());
        payload.put("batchId", a.batchId());
        payload.put("locationId", a.locationId());
        payload.put("status", "AVAILABLE");
        payload.put("qtyDelta", a.qtyDelta());
        payload.put("uomCode", a.uomCode());
        payload.put("reason", a.reason());

        Map<String, Object> body = new HashMap<>();
        // One event stream per warehouse+SKU, mirroring the inventory stock keying.
        body.put("streamId", "stock-" + a.warehouseId() + "-" + a.skuId());
        body.put("eventType", EVENT_TYPE);
        body.put("occurredAt", Instant.now());
        body.put("actor", a.actor() == null ? "station" : a.actor());
        body.put("payload", payload);
        body.put("payloadVersion", 1);

        EventView event = http.post()
                .uri("/api/txlog/events")
                .body(body)
                .retrieve()
                .body(EventView.class);
        return event == null ? null : event.eventId();
    }

    /** Subset of the txlog append response. */
    private record EventView(UUID eventId) {
    }
}
