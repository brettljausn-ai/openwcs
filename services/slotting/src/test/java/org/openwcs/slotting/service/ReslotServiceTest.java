package org.openwcs.slotting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.slotting.client.MasterDataClient;
import org.openwcs.slotting.client.MasterDataClient.StorageLocation;
import org.openwcs.slotting.domain.BlockPolicy;
import org.openwcs.slotting.domain.PutawayAssignment;
import org.openwcs.slotting.domain.ReslotRecommendation;
import org.openwcs.slotting.domain.StorageProfile;
import org.openwcs.slotting.repo.BlockPolicyRepository;
import org.openwcs.slotting.repo.PutawayAssignmentRepository;
import org.openwcs.slotting.repo.StorageProfileRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Re-slotting over PostgreSQL with master-data mocked. */
@SpringBootTest
@Testcontainers
class ReslotServiceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    ReslotService service;
    @Autowired
    StorageProfileRepository profiles;
    @Autowired
    BlockPolicyRepository policies;
    @Autowired
    PutawayAssignmentRepository assignments;

    @MockBean
    MasterDataClient masterData;

    private static StorageLocation loc(UUID id, String aisle, double dist) {
        return new StorageLocation(id, "L", "STORAGE", "ASRS_SLOT", aisle, 1, 3, BigDecimal.valueOf(dist), null, "ACTIVE");
    }

    private BlockPolicy velocityOnlyPolicy(UUID wh, UUID block, boolean enabled) {
        BlockPolicy p = new BlockPolicy();
        p.setWarehouseId(wh);
        p.setBlockId(block);
        p.setWVelocity(BigDecimal.ONE);
        p.setWConsolidation(BigDecimal.ZERO);
        p.setWRedundancy(BigDecimal.ZERO);
        p.setWBalance(BigDecimal.ZERO);
        p.setReslotShiftPct(new BigDecimal("0.1"));
        p.setReslotEnabled(enabled);
        return policies.save(p);
    }

    @Test
    void recommendsMovingAFastMoverTowardTheExit() {
        UUID wh = UUID.randomUUID();
        UUID block = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        UUID hu = UUID.randomUUID();
        UUID near = UUID.randomUUID();
        UUID far = UUID.randomUUID();

        StorageProfile profile = new StorageProfile();
        profile.setWarehouseId(wh);
        profile.setSkuId(sku);
        profile.setBlockId(block);
        profile.setVelocityClass("A");
        profile.setMaxAislePct(BigDecimal.ONE);
        profiles.save(profile);

        velocityOnlyPolicy(wh, block, true);

        PutawayAssignment a = new PutawayAssignment();
        a.setWarehouseId(wh);
        a.setSkuId(sku);
        a.setBlockId(block);
        a.setChosenLocationId(far); // an A-mover stuck deep
        a.setHuId(hu);
        a.setMode("RESERVE");
        a.setStatus("PLANNED");
        assignments.save(a);

        when(masterData.storageLocations(eq(wh), eq(block)))
                .thenReturn(List.of(loc(near, "A2", 1.0), loc(far, "A1", 10.0)));

        List<ReslotRecommendation> recs = service.recommendForBlock(wh, block);

        assertThat(recs).hasSize(1);
        assertThat(recs.get(0).getFromLocationId()).isEqualTo(far);
        assertThat(recs.get(0).getToLocationId()).isEqualTo(near);
    }

    @Test
    void doesNothingWhenReslotDisabled() {
        UUID wh = UUID.randomUUID();
        UUID block = UUID.randomUUID();
        velocityOnlyPolicy(wh, block, false);
        when(masterData.storageLocations(any(), any())).thenReturn(List.of());

        assertThat(service.recommendForBlock(wh, block)).isEmpty();
    }
}
