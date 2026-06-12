package org.openwcs.txlog.repo;

import java.util.List;
import java.util.UUID;
import org.openwcs.txlog.domain.Event;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventRepository extends JpaRepository<Event, UUID> {

    /** Replay a single aggregate's stream in order. */
    List<Event> findByStreamIdOrderBySeqAsc(String streamId);

    /** Global replay feed after a position cursor (build.md §5.4). */
    List<Event> findByPositionGreaterThanOrderByPositionAsc(long position, Pageable pageable);

    /** Highest per-stream sequence (0 if the stream is empty) — used to assign the next seq. */
    @Query("select coalesce(max(e.seq), 0) from Event e where e.streamId = :streamId")
    long maxSeq(@Param("streamId") String streamId);

    /**
     * Wipe the journal + outbox (demo-mode reset, §4.8). The events table is append-only by
     * design: a row-level BEFORE UPDATE OR DELETE trigger rejects ordinary deletes, which is
     * exactly right in production. TRUNCATE does not fire row-level triggers, making it the one
     * deliberate admin escape hatch — and since outbox carries a FK to events, both tables go in
     * a single statement. Transactional in PostgreSQL.
     */
    @Modifying
    @Query(value = "truncate table transaction_log.outbox, transaction_log.events", nativeQuery = true)
    void truncateJournal();
}
