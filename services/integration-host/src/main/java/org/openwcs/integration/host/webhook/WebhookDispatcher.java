package org.openwcs.integration.host.webhook;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.openwcs.integration.host.client.TxLogClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Pushes transaction-log confirmations to each registered webhook. One {@link #dispatchOnce()}
 * pass reads the events after a subscription's cursor and POSTs each to its callback URL;
 * the cursor advances only past events that were delivered (2xx), so delivery is at-least-once
 * and a failing endpoint is retried on the next pass without losing position.
 */
@Service
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    private final WebhookSubscriptionRepository subscriptions;
    private final TxLogClient txLog;
    private final RestClient http;
    private final int batchSize;

    public WebhookDispatcher(WebhookSubscriptionRepository subscriptions, TxLogClient txLog,
                             RestClient.Builder builder,
                             @Value("${openwcs.host.webhook.batch-size:100}") int batchSize) {
        this.subscriptions = subscriptions;
        this.txLog = txLog;
        this.http = builder.build();
        this.batchSize = batchSize;
    }

    @Transactional
    public void dispatchOnce() {
        for (WebhookSubscription sub : subscriptions.findByActiveTrue()) {
            deliver(sub);
        }
    }

    private void deliver(WebhookSubscription sub) {
        long startCursor = sub.getCursor();
        int delivered = 0;
        List<TxLogClient.TxEvent> events = txLog.feed(sub.getCursor(), batchSize);
        for (TxLogClient.TxEvent event : events) {
            Map<String, Object> confirmation = new HashMap<>();
            confirmation.put("cursor", event.position());
            confirmation.put("type", event.eventType());
            confirmation.put("reference", event.streamId());
            confirmation.put("occurredAt", event.occurredAt());
            confirmation.put("actor", event.actor());
            confirmation.put("payload", event.payload());
            try {
                http.post().uri(java.net.URI.create(sub.getCallbackUrl()))
                        .body(confirmation)
                        .retrieve()
                        .toBodilessEntity();
            } catch (RestClientException e) {
                // Delivery failed: stop here and retry from this cursor on the next pass.
                log.warn("webhook delivery to {} (subscription {}) failed at tx-log position {} ({} {}): {}; cursor stays at {}, the event will be retried on the next pass",
                        sub.getCallbackUrl(), sub.getId(), event.position(), event.eventType(),
                        event.streamId(), e.toString(), sub.getCursor());
                return;
            }
            sub.setCursor(event.position());
            delivered++;
        }
        if (delivered > 0) {
            log.info("webhook confirmations delivered to {} (subscription {}): {} events, cursor advanced {} -> {}",
                    sub.getCallbackUrl(), sub.getId(), delivered, startCursor, sub.getCursor());
        }
    }
}
