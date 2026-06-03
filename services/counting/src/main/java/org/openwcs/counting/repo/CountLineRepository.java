package org.openwcs.counting.repo;

import java.util.List;
import java.util.UUID;
import org.openwcs.counting.domain.CountLine;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CountLineRepository extends JpaRepository<CountLine, UUID> {

    List<CountLine> findByCountTaskId(UUID countTaskId);
}
