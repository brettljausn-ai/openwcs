package org.openwcs.orders.repo;

import java.util.List;
import org.openwcs.orders.domain.OrderOutboxMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderOutboxRepository extends JpaRepository<OrderOutboxMessage, Long> {
    List<OrderOutboxMessage> findByPublishedAtIsNullOrderByIdAsc(Pageable pageable);
}
