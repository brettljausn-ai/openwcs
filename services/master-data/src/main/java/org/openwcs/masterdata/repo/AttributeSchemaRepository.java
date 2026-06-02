package org.openwcs.masterdata.repo;

import java.util.List;
import java.util.UUID;
import org.openwcs.masterdata.domain.AttributeSchema;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttributeSchemaRepository extends JpaRepository<AttributeSchema, UUID> {
    List<AttributeSchema> findByWarehouseIdAndAppliesTo(UUID warehouseId, String appliesTo);
}
