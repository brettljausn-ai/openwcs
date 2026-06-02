package org.openwcs.masterdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openwcs.masterdata.domain.Sku;
import org.openwcs.masterdata.domain.SkuProfile;
import org.openwcs.masterdata.domain.Warehouse;
import org.openwcs.masterdata.repo.BarcodeTypeRepository;
import org.openwcs.masterdata.repo.SkuProfileRepository;
import org.openwcs.masterdata.repo.SkuRepository;
import org.openwcs.masterdata.repo.WarehouseRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots the master-data context against a real PostgreSQL 16. This alone runs the
 * Flyway migrations and then Hibernate {@code ddl-auto=validate}, so a clean context
 * start proves every entity mapping matches the migrated schema. A round-trip also
 * exercises UUID generation and JSONB column mapping.
 */
@SpringBootTest
@Testcontainers
class MasterDataPersistenceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    WarehouseRepository warehouses;

    @Autowired
    SkuRepository skus;

    @Autowired
    SkuProfileRepository profiles;

    @Autowired
    BarcodeTypeRepository barcodeTypes;

    @Test
    void persistsSkuProfileOverlayWithJsonbMetadata() {
        Warehouse wh = new Warehouse();
        wh.setCode("WH1");
        wh.setName("Test DC");
        wh = warehouses.save(wh);

        Sku sku = new Sku();
        sku.setCode("SKU-001");
        sku.setDescription("Test tee");
        sku.setBatchTracked(true);
        sku.setDateTracked(true);
        sku = skus.save(sku);

        SkuProfile profile = new SkuProfile();
        profile.setSkuId(sku.getId());
        profile.setWarehouseId(wh.getId());
        profile.setMetadata(Map.of("brand", "Acme", "season", "SS26"));
        profile.setStorageStrategy(Map.of("allowed", "AUTOSTORE"));
        profiles.save(profile);

        assertThat(sku.getId()).isNotNull();
        assertThat(skus.findByCode("SKU-001")).isPresent();
        assertThat(profiles.findBySkuIdAndWarehouseId(sku.getId(), wh.getId()))
                .get()
                .extracting(p -> p.getMetadata().get("brand"))
                .isEqualTo("Acme");
    }

    @Test
    void seedsReferenceBarcodeTypes() {
        // V2 seed migration ships the standard symbologies.
        assertThat(barcodeTypes.findByName("GS1-128"))
                .get()
                .extracting(bt -> bt.isGs1AiParsing())
                .isEqualTo(true);
    }
}
