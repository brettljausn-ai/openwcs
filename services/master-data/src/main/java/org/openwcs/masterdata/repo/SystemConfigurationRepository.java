package org.openwcs.masterdata.repo;

import org.openwcs.masterdata.domain.SystemConfiguration;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SystemConfigurationRepository extends JpaRepository<SystemConfiguration, String> {
}
