package org.openwcs.orders.repo;

import java.util.UUID;
import org.openwcs.orders.domain.OrderLineTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderLineTransactionRepository extends JpaRepository<OrderLineTransaction, UUID> {
}
