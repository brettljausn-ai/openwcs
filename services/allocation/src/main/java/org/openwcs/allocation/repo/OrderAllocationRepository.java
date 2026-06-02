package org.openwcs.allocation.repo;

import java.util.Optional;
import java.util.UUID;
import org.openwcs.allocation.domain.OrderAllocation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderAllocationRepository extends JpaRepository<OrderAllocation, UUID> {
    Optional<OrderAllocation> findByOrderRef(String orderRef);
}
