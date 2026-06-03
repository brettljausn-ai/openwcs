package org.openwcs.iam.repo;

import org.openwcs.iam.domain.ScreenAccess;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScreenAccessRepository extends JpaRepository<ScreenAccess, String> {
}
