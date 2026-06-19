package org.openwcs.processdesigner.repo;

import java.util.List;
import java.util.UUID;
import org.openwcs.processdesigner.domain.ProcessStepEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcessStepEventRepository extends JpaRepository<ProcessStepEvent, UUID> {

    /** The instance's step history in order (oldest first), for replay / resume. */
    List<ProcessStepEvent> findByInstanceIdOrderBySeq(UUID instanceId);

    /** True if an advance at this (instance, seq) was already recorded (idempotency guard). */
    boolean existsByInstanceIdAndSeq(UUID instanceId, long seq);

    /** The highest seq recorded for the instance, or null if it has no events yet. */
    @Query("select max(e.seq) from ProcessStepEvent e where e.instanceId = :instanceId")
    Long maxSeq(@Param("instanceId") UUID instanceId);
}
