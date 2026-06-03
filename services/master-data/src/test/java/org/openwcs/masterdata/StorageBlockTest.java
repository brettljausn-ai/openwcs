package org.openwcs.masterdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openwcs.masterdata.domain.Location;
import org.openwcs.masterdata.domain.StorageBlock;
import org.openwcs.masterdata.domain.Warehouse;
import org.openwcs.masterdata.repo.LocationRepository;
import org.openwcs.masterdata.repo.StorageBlockRepository;
import org.openwcs.masterdata.repo.WarehouseRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots master-data against PostgreSQL 16 to prove the V8 migration (storage_block +
 * rack-lane columns on location) matches the JPA mappings, and exercises the
 * block-scoped location query the slotting put-away engine relies on.
 */
@SpringBootTest
@Testcontainers
class StorageBlockTest {

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
    StorageBlockRepository blocks;

    @Autowired
    LocationRepository locations;

    @Test
    void persistsBlockAndMultiDeepLaneAttributes() {
        Warehouse wh = new Warehouse();
        wh.setCode("WH-SB1");
        wh.setName("Slotting DC");
        wh = warehouses.save(wh);

        StorageBlock block = new StorageBlock();
        block.setWarehouseId(wh.getId());
        block.setCode("ASRS-1");
        block.setStorageType("SHUTTLE_ASRS");
        block.setSlottingGranularity("BLOCK");
        block.setGtp(true);
        block.setAllowedHuTypes(java.util.List.of("TOTE", "TRAY")); // this area only stores totes/trays
        block = blocks.save(block);

        assertThat(block.getId()).isNotNull();
        assertThat(blocks.findByWarehouseIdAndStorageType(wh.getId(), "SHUTTLE_ASRS")).hasSize(1);
        assertThat(blocks.findById(block.getId()).orElseThrow().getAllowedHuTypes()).containsExactly("TOTE", "TRAY");

        Location lane = new Location();
        lane.setWarehouseId(wh.getId());
        lane.setCode("A01-X1-Y1");
        lane.setLocationType("ASRS_SLOT");
        lane.setPurpose("STORAGE");
        lane.setBlockId(block.getId());
        lane.setAisle("A01");
        lane.setRackLevel(1);
        lane.setLaneDepth(3); // triple-deep
        lane.setDistanceToExit(new BigDecimal("12.5"));
        locations.save(lane);

        List<Location> pool = locations.findByWarehouseIdAndBlockId(wh.getId(), block.getId());
        assertThat(pool).hasSize(1);
        Location got = pool.get(0);
        assertThat(got.getLaneDepth()).isEqualTo(3);
        assertThat(got.getAisle()).isEqualTo("A01");
        assertThat(got.getDistanceToExit()).isEqualByComparingTo("12.5");
    }
}
