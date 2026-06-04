package org.openwcs.iam.repo;

import java.util.List;
import org.openwcs.iam.domain.UserWarehouse;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserWarehouseRepository extends JpaRepository<UserWarehouse, UserWarehouse.Key> {

    List<UserWarehouse> findByUsername(String username);

    void deleteByUsername(String username);
}
