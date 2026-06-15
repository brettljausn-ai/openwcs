package org.openwcs.orders.repo;

import java.util.Optional;
import java.util.UUID;
import org.openwcs.orders.domain.OrderLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderLineRepository extends JpaRepository<OrderLine, UUID> {

    /** Load a line with its owning order + posted transactions for pick confirmation. */
    @Query("""
        select l from OrderLine l
        join fetch l.order
        left join fetch l.transactions
        where l.id = :lineId
        """)
    Optional<OrderLine> findByIdWithOrder(@Param("lineId") UUID lineId);
}
