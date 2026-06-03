package org.openwcs.slotting.velocity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.openwcs.common.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Subscribes to the streamed transaction log (topic {@code txlog.stream}, build.md §9) and feeds
 * each event to the {@link VelocityProjectionService}, which counts pick/outbound movements per
 * SKU. Mirrors {@code inventory.TransactionLogConsumer}: envelopes are JSON; the listener commits
 * the Kafka offset only after this method returns normally (ack-mode {@code record}), giving
 * at-least-once delivery, and the velocity inbox makes the re-delivery idempotent.
 */
@Component
public class VelocityTransactionLogConsumer {

    private static final Logger log = LoggerFactory.getLogger(VelocityTransactionLogConsumer.class);

    private final VelocityProjectionService projection;
    private final ObjectMapper objectMapper;

    public VelocityTransactionLogConsumer(VelocityProjectionService projection, ObjectMapper objectMapper) {
        this.projection = projection;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${openwcs.slotting.txlog-topic:txlog.stream}",
            groupId = "${spring.kafka.consumer.group-id:slotting-velocity-learner}")
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
