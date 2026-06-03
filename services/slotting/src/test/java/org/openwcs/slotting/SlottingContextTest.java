package org.openwcs.slotting;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.slotting.domain.BlockPolicy;
import org.openwcs.slotting.domain.PickSlot;
import org.openwcs.slotting.domain.StorageProfile;
import org.openwcs.slotting.repo.BlockPolicyRepository;
import org.openwcs.slotting.repo.PickSlotRepository;
import org.openwcs.slotting.repo.StorageProfileRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots the slotting context against PostgreSQL 16 — running Flyway V1 then Hibernate
 * {@code ddl-auto=validate}, so a clean start proves every entity mapping matches the migrated
 * schema. Round-trips the config entities (defaults, JSON mapping, unique keys).
 */
@SpringBootTest
@Testcontainers
class SlottingContextTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    StorageProfileRepository profiles;

    @Autowired
    PickSlotRepository pickSlots;

    @Autowired
    BlockPolicyRepository policies;

    @Test
    void roundTripsConfigEntities() {
        UUID wh = UUID.randomUUID();
        UUID block = UUID.randomUUID();
        UUID sku = UUID.randomUUID();

        StorageProfile profile = new StorageProfile();
        profile.setWarehouseId(wh);
        profile.setSkuId(sku);
        profile.setBlockId(block);
        profile.setVelocityClass("A");
        profile.setMinAisles(2);
        profile.setMaxAislePct(new BigDecimal("0.5"));
        profiles.save(profile);

        PickSlot slot = new PickSlot();
        slot.setWarehouseId(wh);
        slot.setLocationId(UUID.randomUUID());
        slot.setSkuId(sku);
        slot.setUomId(UUID.randomUUID());
        slot.setMinQty(new BigDecimal("12"));
        slot.setMaxQty(new BigDecimal("48"));
        slot.setDirectToPick(true);
        pickSlots.save(slot);

        BlockPolicy policy = new BlockPolicy();
        policy.setBlockId(block);
        policy.setWarehouseId(wh);
        policies.save(policy);

        assertThat(profiles.findByWarehouseIdAndSkuIdAndBlockId(wh, sku, block)).isPresent();
        assertThat(pickSlots.findByWarehouseIdAndSkuId(wh, sku)).hasSize(1);
        BlockPolicy saved = policies.findByBlockId(block).orElseThrow();
        // defaults from the migration / entity
        assertThat(saved.getMinAislesA()).isEqualTo(2);
        assertThat(saved.getDefaultMaxAislePct()).isEqualByComparingTo("0.5");
    }
}
