package org.openwcs.inventory.projection;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.openwcs.common.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Subscribes to the streamed transaction log (topic {@code txlog.stream}, build.md §9)
 * and feeds each event to the {@link StockProjectionService}. Envelopes are carried as
 * JSON; the payload is decoded per event type inside the projection.
 *
 * <p>The listener container commits the Kafka offset only after this method returns
 * normally (ack-mode {@code record}), giving at-least-once delivery; the projection's
 * inbox makes the re-delivery idempotent.
 */
@Component
public class TransactionLogConsumer {

    private static final Logger log = LoggerFactory.getLogger(TransactionLogConsumer.class);

    private final StockProjectionService projection;
    private final ObjectMapper objectMapper;

    public TransactionLogConsumer(StockProjectionService projection, ObjectMapper objectMapper) {
        this.projection = projection;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${openwcs.inventory.txlog-topic:txlog.stream}",
            groupId = "${spring.kafka.consumer.group-id:inventory-stock-projection}")
    public void onTransactionLogEvent(String message) {
        EventEnvelope envelope;
        try {
            envelope = objectMapper.readValue(message, EventEnvelope.class);
        } catch (Exception e) {
            // Malformed payload: re-throw so the container error handler can retry / DLQ
            // (build.md §12). A poison message must not be silently dropped.
            log.error("Could not parse transaction-log message: {}", message, e);
            throw new IllegalStateException("Unparseable transaction-log message", e);
        }
        projection.apply(envelope);
    }
}
