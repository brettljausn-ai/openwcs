package org.openwcs.txlog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openwcs.txlog.domain.OutboxMessage;
import org.openwcs.txlog.relay.OutboxRelay;
import org.openwcs.txlog.repo.OutboxRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/** Unit test for the relay: publishes the backlog, marks rows sent, and stops on failure. */
@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    @Mock
    OutboxRepository outbox;

    @Mock
    KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void publishesPendingAndMarksSent() {
        OutboxMessage message = new OutboxMessage(UUID.randomUUID(), "txlog.stream", "HU-1", "{\"k\":1}");
        when(outbox.findByPublishedAtIsNullOrderByIdAsc(any())).thenReturn(List.of(message));
        CompletableFuture<SendResult<String, String>> done = CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(done);

        new OutboxRelay(outbox, kafkaTemplate, 100).publishPending();

        verify(kafkaTemplate).send("txlog.stream", "HU-1", "{\"k\":1}");
        assertThat(message.getPublishedAt()).isNotNull();
    }

    @Test
    void stopsAtFirstFailureWithoutMarkingSent() {
        OutboxMessage message = new OutboxMessage(UUID.randomUUID(), "txlog.stream", "HU-1", "{\"k\":1}");
        when(outbox.findByPublishedAtIsNullOrderByIdAsc(any())).thenReturn(List.of(message));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("broker down"));

        new OutboxRelay(outbox, kafkaTemplate, 100).publishPending();

        assertThat(message.getPublishedAt()).isNull();
        assertThat(message.getAttempts()).isEqualTo(1);
    }

    private static Pageable any() {
        return org.mockito.ArgumentMatchers.any(Pageable.class);
    }
}
