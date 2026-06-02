package org.openwcs.masterdata.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.openwcs.masterdata.domain.Location;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LocationRepository extends JpaRepository<Location, UUID> {
    Optional<Location> findByWarehouseIdAndCode(UUID warehouseId, String code);

    List<Location> findByWarehouseIdAndPurpose(UUID warehouseId, String purpose);

    List<Location> findByParentId(UUID parentId);
}
