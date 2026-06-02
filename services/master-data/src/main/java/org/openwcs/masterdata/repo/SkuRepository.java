package org.openwcs.masterdata.repo;

import java.util.Optional;
import java.util.UUID;
import org.openwcs.masterdata.domain.Sku;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SkuRepository extends JpaRepository<Sku, UUID> {
    Optional<Sku> findByCode(String code);

    Page<Sku> findByOwnerClientIgnoreCase(String ownerClient, Pageable pageable);

    /** Free-text search on code or description. */
    @Query("""
        select s from Sku s
        where lower(s.code) like lower(concat('%', :q, '%'))
           or lower(s.description) like lower(concat('%', :q, '%'))
        """)
    Page<Sku> search(@Param("q") String q, Pageable pageable);
}
