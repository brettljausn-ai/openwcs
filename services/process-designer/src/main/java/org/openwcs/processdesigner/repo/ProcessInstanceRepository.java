package org.openwcs.processdesigner.repo;

import java.util.List;
import java.util.UUID;
import org.openwcs.processdesigner.domain.ProcessInstance;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcessInstanceRepository extends JpaRepository<ProcessInstance, UUID> {

    /**
     * Instance monitoring list (spec §14): newest-first, optionally filtered by processKey / status /
     * warehouseId (a null filter matches all). Capped by the {@link Pageable} the caller passes.
     */
    @Query("""
            select i from ProcessInstance i
            where (:processKey is null or i.processKey = :processKey)
              and (:status is null or i.status = :status)
              and (:warehouseId is null or i.warehouseId = :warehouseId)
            order by i.startedAt desc
            """)
    List<ProcessInstance> search(@Param("processKey") String processKey,
                                 @Param("status") String status,
                                 @Param("warehouseId") UUID warehouseId,
                                 Pageable pageable);
}
