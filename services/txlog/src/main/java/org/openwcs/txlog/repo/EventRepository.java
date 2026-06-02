package org.openwcs.txlog.repo;

import java.util.List;
import java.util.UUID;
import org.openwcs.txlog.domain.Event;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
