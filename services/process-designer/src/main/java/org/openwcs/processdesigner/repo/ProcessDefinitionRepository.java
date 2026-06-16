package org.openwcs.processdesigner.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.openwcs.processdesigner.domain.ProcessDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcessDefinitionRepository extends JpaRepository<ProcessDefinition, UUID> {

    Optional<ProcessDefinition> findByProcessKeyAndVersion(String processKey, int version);

    Optional<ProcessDefinition> findByProcessKeyAndStatus(String processKey, String status);

    List<ProcessDefinition> findByStatusOrderByProcessKeyAscVersionDesc(String status);

    List<ProcessDefinition> findAllByOrderByProcessKeyAscVersionDesc();

    @Query("select coalesce(max(d.version), 0) from ProcessDefinition d where d.processKey = :key")
    int maxVersionForKey(@Param("key") String key);
}
