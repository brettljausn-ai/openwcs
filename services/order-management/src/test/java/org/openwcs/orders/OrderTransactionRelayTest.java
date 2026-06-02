package org.openwcs.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openwcs.orders.client.TxLogClient;
import org.openwcs.orders.domain.OrderLineTransaction;
import org.openwcs.orders.domain.OrderOutboxMessage;
import org.openwcs.orders.domain.TransactionType;
import org.openwcs.orders.relay.OrderTransactionRelay;
import org.openwcs.orders.repo.OrderLineTransactionRepository;
import org.openwcs.orders.repo.OrderOutboxRepository;

/** Unit test: the relay appends a staged event to txlog, stamps the event id, and marks it sent. */
@ExtendWith(MockitoExtension.class)
class OrderTransactionRelayTest {

    @Mock
    OrderOutboxRepository outbox;

    @Mock
    OrderLineTransactionRepository transactions;

    @Mock
    TxLogClient txlog;

    @Test
    void publishesPendingAndRecordsEventId() {
        UUID txnId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Map<String, Object> payload = Map.of("warehouseId", UUID.randomUUID(), "qty", 4);

        OrderOutboxMessage message = new OrderOutboxMessage(
                txnId, "stream-line", "GoodsReceived", orderId, "operator-1", payload);
        OrderLineTransaction txn = new OrderLineTransaction(
                null, TransactionType.RECEIPT, BigDecimal.ONE, UUID.randomUUID(), null, null, null, "operator-1");

        when(outbox.findByPublishedAtIsNullOrderByIdAsc(any())).thenReturn(List.of(message));
        when(txlog.append(any(), any(), any(), any(), any())).thenReturn(eventId);
        when(transactions.findById(txnId)).thenReturn(Optional.of(txn));

        new OrderTransactionRelay(outbox, transactions, txlog, 100).publishPending();

        verify(txlog).append("stream-line", "GoodsReceived", orderId, "operator-1", payload);
        assertThat(txn.getEventId()).isEqualTo(eventId);
        assertThat(message.getPublishedAt()).isNotNull();
    }
}
