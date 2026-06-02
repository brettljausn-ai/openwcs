package org.openwcs.allocation.repo;

import java.util.UUID;
import org.openwcs.allocation.domain.PickBatch;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PickBatchRepository extends JpaRepository<PickBatch, UUID> {
}
