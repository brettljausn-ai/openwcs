package org.openwcs.masterdata;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.openwcs.masterdata.domain.Location;
import org.openwcs.masterdata.domain.Shipper;
import org.openwcs.masterdata.domain.Sku;
import org.openwcs.masterdata.domain.Warehouse;
import org.openwcs.masterdata.domain.WarehouseFulfillmentConfig;
import org.openwcs.masterdata.repo.BarcodeRepository;
import org.openwcs.masterdata.repo.LocationRepository;
import org.openwcs.masterdata.repo.ShipperRepository;
import org.openwcs.masterdata.repo.SkuRepository;
import org.openwcs.masterdata.repo.UnitOfMeasureRepository;
import org.openwcs.masterdata.repo.WarehouseFulfillmentConfigRepository;
import org.openwcs.masterdata.repo.WarehouseRepository;
import org.openwcs.masterdata.service.DemoSeedService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Demo mode OFF is a full catalog reset (build.md §4.8): the WHOLE SKU catalog goes (bulk
 * DELETEs, not row-by-row), demo shippers and the demo HU type are removed, and a cubing-config
 * default shipper pointing at a demo carton is unset first — that FK used to fail the disable
 * with a 409 "request conflicts with existing data".
 */
@SpringBootTest
@Testcontainers
class DemoModeResetTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    DemoSeedService demo;

    @Autowired
    WarehouseRepository warehouses;

    @Autowired
    LocationRepository locations;

    @Autowired
    ShipperRepository shippers;

    @Autowired
    SkuRepository skus;

    @Autowired
    UnitOfMeasureRepository uoms;

    @Autowired
    BarcodeRepository barcodes;

    @Autowired
    WarehouseFulfillmentConfigRepository fulfillmentConfigs;

    @Test
    void disableResetsTheWholeCatalogAndUnsetsTheDemoDefaultShipper() {
        Warehouse wh = new Warehouse();
        wh.setCode("DEMO-WH");
        wh.setName("Demo reset test DC");
        wh.setTimezone("UTC");
        wh.setStatus("ACTIVE");
        wh = warehouses.save(wh);

        Location loc = new Location();
        loc.setWarehouseId(wh.getId());
        loc.setCode("LOC-1");
        loc.setLocationType("BIN");
        loc.setPurpose("STORAGE");
        loc.setStatus("ACTIVE");
        locations.save(loc);

        demo.enable(wh.getId());
        assertThat(skus.count()).isPositive();
        assertThat(uoms.count()).isPositive();
        assertThat(barcodes.count()).isPositive();

        // An admin picked a demo carton as the cubing default — this shipper FK used to make
        // the disable fail with a DataIntegrityViolation (409).
        Shipper demoCarton = shippers.findByWarehouseIdAndCode(wh.getId(), "DEMO-CARTON-L").orElseThrow();
        WarehouseFulfillmentConfig cfg = new WarehouseFulfillmentConfig();
        cfg.setWarehouseId(wh.getId());
        cfg.setDefaultShipperId(demoCarton.getId());
        fulfillmentConfigs.save(cfg);

        // A non-DEMO-prefixed SKU is part of the reset too: disable wipes the whole catalog,
        // not just DEMO- rows.
        Sku stray = new Sku();
        stray.setCode("HOST-001");
        stray.setDescription("Stray host SKU");
        stray.setStatus("ACTIVE");
        skus.save(stray);

        demo.disable();

        assertThat(skus.count()).isZero();
        assertThat(uoms.count()).isZero();
        assertThat(barcodes.count()).isZero();
        assertThat(shippers.findByWarehouseId(wh.getId())).isEmpty();
        assertThat(fulfillmentConfigs.findByWarehouseId(wh.getId()).orElseThrow().getDefaultShipperId()).isNull();
        assertThat(demo.status(wh.getId()).enabled()).isFalse();
    }
}
