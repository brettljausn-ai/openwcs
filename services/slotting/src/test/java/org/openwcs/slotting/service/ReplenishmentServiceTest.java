package org.openwcs.slotting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.slotting.domain.PickSlot;
import org.openwcs.slotting.domain.ReplenishmentTask;
import org.openwcs.slotting.client.InventoryClient;
import org.openwcs.slotting.repo.PickSlotRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Replenishment planning over PostgreSQL with the inventory projection mocked. */
@SpringBootTest
@Testcontainers
class ReplenishmentServiceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    ReplenishmentService service;

    @Autowired
    PickSlotRepository pickSlots;

    @MockBean
    InventoryClient inventory;

    private UUID seedSlot() {
        UUID wh = UUID.randomUUID();
        PickSlot slot = new PickSlot();
        slot.setWarehouseId(wh);
        slot.setLocationId(UUID.randomUUID());
        slot.setSkuId(UUID.randomUUID());
        slot.setUomId(UUID.randomUUID());
        slot.setMinQty(new BigDecimal("12"));
        slot.setMaxQty(new BigDecimal("48"));
        pickSlots.save(slot);
        return wh;
    }

    @Test
    void emergencyWhenFaceIsEmpty() {
        UUID wh = seedSlot();
        when(inventory.onHandAtLocation(any(), any(), any())).thenReturn(BigDecimal.ZERO);

        List<ReplenishmentTask> created = service.planBelowMin(wh);

        assertThat(created).hasSize(1);
        assertThat(created.get(0).getPriority()).isEqualTo("EMERGENCY");
        assertThat(created.get(0).getTriggerType()).isEqualTo("BELOW_MIN");
        assertThat(created.get(0).getQty()).isEqualByComparingTo("48");
    }

    @Test
    void scheduledWhenBelowMinButNotEmpty() {
        UUID wh = seedSlot();
        when(inventory.onHandAtLocation(any(), any(), any())).thenReturn(new BigDecimal("5"));

        List<ReplenishmentTask> created = service.planBelowMin(wh);

        assertThat(created).hasSize(1);
        assertThat(created.get(0).getPriority()).isEqualTo("SCHEDULED");
        assertThat(created.get(0).getQty()).isEqualByComparingTo("43");
    }

    @Test
    void topOffIsOpportunisticAndBelowMinDoesNothingAboveMin() {
        UUID wh = seedSlot();
        when(inventory.onHandAtLocation(any(), any(), any())).thenReturn(new BigDecimal("30"));

        assertThat(service.planBelowMin(wh)).isEmpty(); // 30 > min(12)

        List<ReplenishmentTask> topped = service.topOff(wh);
        assertThat(topped).hasSize(1);
        assertThat(topped.get(0).getPriority()).isEqualTo("OPPORTUNISTIC");
        assertThat(topped.get(0).getTriggerType()).isEqualTo("TOP_OFF");
        assertThat(topped.get(0).getQty()).isEqualByComparingTo("18");
    }

    @Test
    void deduplicatesAgainstAnOpenTask() {
        UUID wh = seedSlot();
        when(inventory.onHandAtLocation(any(), any(), any())).thenReturn(BigDecimal.ZERO);

        assertThat(service.planBelowMin(wh)).hasSize(1);
        assertThat(service.planBelowMin(wh)).isEmpty(); // open task already exists for the face
    }
}
