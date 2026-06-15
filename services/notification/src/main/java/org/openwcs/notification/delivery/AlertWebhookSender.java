package org.openwcs.notification.delivery;

import java.util.LinkedHashMap;
import java.util.Map;
import org.openwcs.notification.domain.AlertEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Webhook delivery channel: POSTs a small JSON payload to a configurable URL on OPEN / CLEAR. When
 * {@code ALERT_WEBHOOK_URL} is unset the channel is skipped. Like email, every send is isolated — a
 * failure logs WARN and never propagates back into evaluation.
 */
@Component
public class AlertWebhookSender implements AlertDelivery {

    private static final Logger log = LoggerFactory.getLogger(AlertWebhookSender.class);

    private final RestClient http;
    private final String webhookUrl;

    public AlertWebhookSender(
            RestClient.Builder builder,
            @Value("${openwcs.notification.webhook-url:}") String webhookUrl) {
        this.http = builder.build();
        this.webhookUrl = webhookUrl;
    }

    @Override
    public void onOpen(AlertEvent alert) {
        post("OPENED", alert);
    }

    @Override
    public void onClear(AlertEvent alert) {
        post("CLEARED", alert);
    }

    private void post(String transition, AlertEvent a) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.debug("webhook channel not configured (ALERT_WEBHOOK_URL unset) — skipping {} for {}",
                    transition, a.getMetric());
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transition", transition);
        payload.put("id", a.getId());
        payload.put("warehouseId", a.getWarehouseId());
        payload.put("area", a.getArea());
        payload.put("metric", a.getMetric());
        payload.put("severity", a.getSeverity());
        payload.put("value", a.getValue());
        payload.put("threshold", a.getThreshold());
        payload.put("state", a.getState());
        payload.put("since", a.getOpenedAt());
        try {
            http.post().uri(webhookUrl).body(payload).retrieve().toBodilessEntity();
            log.info("alert webhook delivered ({}) for {}", transition, a.getMetric());
        } catch (Exception e) {
            log.warn("alert webhook delivery failed for {}: {}", a.getMetric(), e.toString());
        }
    }
}
