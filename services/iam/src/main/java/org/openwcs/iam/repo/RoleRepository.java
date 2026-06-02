package org.openwcs.iam.repo;

import java.util.Optional;
import java.util.UUID;
import org.openwcs.iam.domain.Role;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, UUID> {
    Optional<Role> findByName(String name);
}
