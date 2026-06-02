package org.openwcs.iam.repo;

import java.util.Optional;
import java.util.UUID;
import org.openwcs.iam.domain.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
    Optional<AppUser> findByUsername(String username);
}
