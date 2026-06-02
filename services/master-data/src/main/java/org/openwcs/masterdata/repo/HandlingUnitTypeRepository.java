package org.openwcs.masterdata.repo;

import java.util.Optional;
import java.util.UUID;
import org.openwcs.masterdata.domain.HandlingUnitType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HandlingUnitTypeRepository extends JpaRepository<HandlingUnitType, UUID> {
    Optional<HandlingUnitType> findByName(String name);
}
