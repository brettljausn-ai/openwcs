package org.openwcs.txlog.relay;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.openwcs.txlog.domain.OutboxMessage;
import org.openwcs.txlog.repo.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Polls the transactional outbox and publishes unsent rows to Kafka (topic
 * {@code txlog.stream}, build.md §5.5, §9), then stamps {@code publishedAt}. Rows are
 * sent strictly in insertion order and the batch stops at the first failure, so an
 * event is never published before an earlier one from the same stream. Delivery is
 * at-least-once; downstream consumers dedupe on {@code event_id}.
 *
 * <p>Disabled in tests via {@code openwcs.txlog.relay.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "openwcs.txlog.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final int batchSize;

    public OutboxRelay(OutboxRepository outbox,
                       KafkaTemplate<String, String> kafkaTemplate,
                       @org.springframework.beans.factory.annotation.Value("${openwcs.txlog.relay.batch-size:100}") int batchSize) {
        this.outbox = outbox;
        this.kafkaTemplate = kafkaTemplate;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${openwcs.txlog.relay.interval-ms:1000}")
    @SchedulerLock(name = "txlog-outbox-relay", lockAtMostFor = "PT1M")
    @Transactional
    public void publishPending() {
        List<OutboxMessage> batch = outbox.findByPublishedAtIsNullOrderByIdAsc(PageRequest.of(0, batchSize));
        if (batch.isEmpty()) {
            return;
        }
        int published = 0;
        for (OutboxMessage message : batch) {
            try {
                kafkaTemplate.send(message.getTopic(), message.getMessageKey(), message.getPayload())
                        .get(SEND_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
                message.markPublished(Instant.now());
                published++;
            } catch (Exception e) {
                // Stop at the first failure to preserve ordering; retry on the next tick.
                message.recordAttempt();
                log.warn("Outbox relay halted at message {} (attempt {}): {}",
                        message.getId(), message.getAttempts(), e.toString());
                break;
            }
        }
        if (published > 0) {
            log.debug("Relayed {} outbox message(s) to Kafka", published);
        }
    }
}
