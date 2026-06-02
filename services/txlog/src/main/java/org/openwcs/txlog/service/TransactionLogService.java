package org.openwcs.txlog.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.openwcs.common.EventEnvelope;
import org.openwcs.txlog.domain.Event;
import org.openwcs.txlog.domain.OutboxMessage;
import org.openwcs.txlog.repo.EventRepository;
import org.openwcs.txlog.repo.OutboxRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends events to the immutable transaction log and stages them for publication via
 * the transactional outbox (build.md §5.2, §5.5). The event row and its outbox row are
 * written in one local transaction, so there is no dual-write gap between persisting an
 * event and making it available on the stream.
 */
@Service
public class TransactionLogService {

    private final EventRepository events;
    private final OutboxRepository outbox;
    private final ObjectMapper objectMapper;
    private final String streamTopic;

    public TransactionLogService(EventRepository events,
                                 OutboxRepository outbox,
                                 ObjectMapper objectMapper,
                                 @Value("${openwcs.txlog.stream-topic:txlog.stream}") String streamTopic) {
        this.events = events;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.streamTopic = streamTopic;
    }

    /**
     * Append one event. Throws {@link org.springframework.dao.DataIntegrityViolationException}
     * if the (streamId, seq) pair already exists — i.e. a concurrency conflict on an
     * explicit {@code expectedSeq}, or a race on auto-assignment.
     */
    @Transactional
    public Event append(AppendCommand cmd) {
        Instant now = Instant.now();
        Instant occurredAt = cmd.occurredAt() != null ? cmd.occurredAt() : now;
        int payloadVersion = cmd.payloadVersion() != null ? cmd.payloadVersion() : 1;
        Map<String, Object> payload = cmd.payload() != null ? cmd.payload() : Map.of();
        long seq = cmd.expectedSeq() != null ? cmd.expectedSeq() : events.maxSeq(cmd.streamId()) + 1;

        Event event = new Event(
                cmd.streamId(), seq, cmd.eventType(), occurredAt, now,
                cmd.actor(), cmd.correlationId(), payload, payloadVersion);
        // Flush now so a (stream_id, seq) collision surfaces inside this call.
        events.saveAndFlush(event);

        EventEnvelope envelope = new EventEnvelope(
                event.getEventId(), event.getStreamId(), event.getSeq(), event.getEventType(),
                event.getOccurredAt(), event.getRecordedAt(), event.getActor(),
                event.getCorrelationId(), event.getPayloadVersion(), payload);
        outbox.save(new OutboxMessage(event.getEventId(), streamTopic, event.getStreamId(), serialize(envelope)));

        return event;
    }

    @Transactional(readOnly = true)
    public List<Event> streamEvents(String streamId) {
        return events.findByStreamIdOrderBySeqAsc(streamId);
    }

    @Transactional(readOnly = true)
    public List<Event> feed(long afterPosition, int limit) {
        return events.findByPositionGreaterThanOrderByPositionAsc(afterPosition, PageRequest.of(0, limit));
    }

    private String serialize(EventEnvelope envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize event envelope " + envelope.eventId(), e);
        }
    }
}
