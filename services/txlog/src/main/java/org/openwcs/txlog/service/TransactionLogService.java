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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
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

    private static final Logger log = LoggerFactory.getLogger(TransactionLogService.class);

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
        try {
            events.saveAndFlush(event);
        } catch (DataIntegrityViolationException e) {
            log.warn("append conflict: stream {} seq {} already exists ({} from actor {},"
                            + " expectedSeq {}); the append is rejected and the caller must"
                            + " reload the stream and retry with the next sequence",
                    cmd.streamId(), seq, cmd.eventType(), cmd.actor(), cmd.expectedSeq());
            throw e;
        }
        log.debug("event appended: {} on stream {} seq {} (event {}) staged for publication on topic {}",
                event.getEventType(), event.getStreamId(), event.getSeq(), event.getEventId(), streamTopic);

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

    /** What {@link #clearAll()} removed. */
    public record ClearCounts(long events, long outboxMessages) {}

    /**
     * Wipe the journal: every event and any unpublished outbox rows (demo-mode reset, §4.8).
     * One TRUNCATE over both tables — never loads the (potentially huge) journal into memory,
     * and deliberately bypasses the append-only row trigger on events (ordinary DELETE is
     * rejected by design; TRUNCATE is the explicit admin reset seam). Position values are never
     * reused (Postgres sequences keep counting), so feed cursors held by consumers stay safe:
     * they simply see no rows until new events arrive.
     */
    @Transactional
    public ClearCounts clearAll() {
        long eventCount = events.count();
        long outboxCount = outbox.count();
        events.truncateJournal();
        log.info("transaction journal cleared: {} events and {} outbox rows truncated"
                + " because of a demo-mode reset; feed positions are not reused, so consumer"
                + " cursors stay valid and simply see no rows until new events arrive",
                eventCount, outboxCount);
        return new ClearCounts(eventCount, outboxCount);
    }

    private String serialize(EventEnvelope envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize event envelope " + envelope.eventId(), e);
        }
    }
}
