package org.openwcs.txlog.repo;

import java.util.List;
import org.openwcs.txlog.domain.OutboxMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxRepository extends JpaRepository<OutboxMessage, Long> {

    /** Unpublished backlog in insertion order (preserves per-stream ordering on publish). */
    List<OutboxMessage> findByPublishedAtIsNullOrderByIdAsc(Pageable pageable);
}
