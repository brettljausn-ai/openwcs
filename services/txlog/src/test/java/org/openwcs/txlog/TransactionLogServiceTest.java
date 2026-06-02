package org.openwcs.txlog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openwcs.txlog.domain.Event;
import org.openwcs.txlog.domain.OutboxMessage;
import org.openwcs.txlog.repo.EventRepository;
import org.openwcs.txlog.repo.OutboxRepository;
import org.openwcs.txlog.service.AppendCommand;
import org.openwcs.txlog.service.TransactionLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots txlog against PostgreSQL 16 (Flyway + Hibernate validate) and exercises the
 * append path: sequence assignment, the immutable event row, the staged outbox row,
 * and the optimistic-concurrency conflict. The relay is disabled (no broker needed).
 */
@SpringBootTest
@Testcontainers
class TransactionLogServiceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("openwcs.txlog.relay.enabled", () -> "false");
    }

    @Autowired
    TransactionLogService service;

    @Autowired
    EventRepository events;

    @Autowired
    OutboxRepository outbox;

    private AppendCommand goodsReceived(String streamId, Long expectedSeq) {
        return new AppendCommand(
                streamId, "GoodsReceived", null, "receiving-1", null,
                Map.of("sku", "SKU-1", "qty", 12), null, expectedSeq);
    }

    @Test
    void appendAssignsSequentialSeqAndStagesOutbox() {
        Event first = service.append(goodsReceived("HU-1", null));
        Event second = service.append(goodsReceived("HU-1", null));

        assertThat(first.getSeq()).isEqualTo(1);
        assertThat(second.getSeq()).isEqualTo(2);
        assertThat(first.getEventId()).isNotNull();

        List<Event> stream = events.findByStreamIdOrderBySeqAsc("HU-1");
        assertThat(stream).hasSize(2);

        // Each append staged exactly one unsent outbox row carrying the serialized envelope.
        List<OutboxMessage> staged = outbox.findAll();
        assertThat(staged).hasSize(2);
        assertThat(staged).allSatisfy(m -> {
            assertThat(m.getPublishedAt()).isNull();
            assertThat(m.getTopic()).isEqualTo("txlog.stream");
            assertThat(m.getMessageKey()).isEqualTo("HU-1");
            assertThat(m.getPayload()).contains("\"eventType\":\"GoodsReceived\"");
        });
    }

    @Test
    void duplicateExpectedSeqIsRejected() {
        service.append(goodsReceived("HU-2", 1L));
        assertThatThrownBy(() -> service.append(goodsReceived("HU-2", 1L)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void perStreamSequencesAreIndependent() {
        service.append(goodsReceived("HU-3", null));
        Event other = service.append(goodsReceived("HU-4", null));
        assertThat(other.getSeq()).isEqualTo(1);
        assertThat(events.maxSeq("HU-3")).isEqualTo(1);
    }
}
