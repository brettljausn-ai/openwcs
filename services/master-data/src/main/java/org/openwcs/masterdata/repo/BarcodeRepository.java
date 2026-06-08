package org.openwcs.masterdata.repo;

import java.util.List;
import java.util.UUID;
import org.openwcs.masterdata.domain.Barcode;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BarcodeRepository extends JpaRepository<Barcode, UUID> {
    List<Barcode> findBySkuId(UUID skuId);

    List<Barcode> findByValue(String value);

    void deleteBySkuId(UUID skuId);
}
