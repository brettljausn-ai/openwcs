package org.openwcs.masterdata.repo;

import java.util.Optional;
import java.util.UUID;
import org.openwcs.masterdata.domain.BarcodeType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BarcodeTypeRepository extends JpaRepository<BarcodeType, UUID> {
    Optional<BarcodeType> findByName(String name);
}
