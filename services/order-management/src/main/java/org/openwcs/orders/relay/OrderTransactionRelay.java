package org.openwcs.orders.relay;

import java.time.Instant;
import java.util.List;
import org.openwcs.orders.client.TxLogClient;
import org.openwcs.orders.domain.OrderOutboxMessage;
import org.openwcs.orders.repo.OrderLineTransactionRepository;
import org.openwcs.orders.repo.OrderOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drains the order-management outbox: appends each pending line-transaction event to the
 * transaction log and records the returned event id on the line transaction (build.md §5.5).
 * At-least-once; stops at the first failure to preserve ordering. Disabled in tests via
 * {@code openwcs.orders.relay.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "openwcs.orders.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OrderTransactionRelay {

    private static final Logger log = LoggerFactory.getLogger(OrderTransactionRelay.class);

    private final OrderOutboxRepository outbox;
    private final OrderLineTransactionRepository transactions;
    private final TxLogClient txlog;
    private final int batchSize;

    public OrderTransactionRelay(OrderOutboxRepository outbox,
                                 OrderLineTransactionRepository transactions,
                                 TxLogClient txlog,
                                 @Value("${openwcs.orders.relay.batch-size:100}") int batchSize) {
        this.outbox = outbox;
        this.transactions = transactions;
        this.txlog = txlog;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${openwcs.orders.relay.interval-ms:1000}")
    @SchedulerLock(name = "order-transaction-relay", lockAtMostFor = "PT1M")
    @Transactional
    public void publishPending() {
        List<OrderOutboxMessage> batch =
                outbox.findByPublishedAtIsNullOrderByIdAsc(PageRequest.of(0, batchSize));
        for (OrderOutboxMessage message : batch) {
            try {
                java.util.UUID eventId = txlog.append(
                        message.getStreamId(), message.getEventType(),
                        message.getCorrelationId(), message.getActor(), message.getPayload());
                transactions.findById(message.getLineTxnId())
                        .ifPresent(txn -> txn.setEventId(eventId));
                message.markPublished(Instant.now());
            } catch (RuntimeException e) {
                message.recordAttempt();
                log.warn("Order outbox relay halted at message {} (attempt {}): {}",
                        message.getId(), message.getAttempts(), e.toString());
                break;
            }
        }
    }
}
