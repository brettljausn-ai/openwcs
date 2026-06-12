package org.openwcs.gtp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.gtp.api.AddNodeRequest;
import org.openwcs.gtp.api.CreateStationRequest;
import org.openwcs.gtp.api.StartCycleRequest;
import org.openwcs.gtp.client.InventoryClient;
import org.openwcs.gtp.client.MasterDataClient;
import org.openwcs.gtp.domain.GtpStation;
import org.openwcs.gtp.domain.WorkCycle;
import org.openwcs.gtp.service.GtpStationService;
import org.openwcs.gtp.service.WorkCycleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The single-SKU-per-compartment stock rule (master-data {@code /stock-rules}, default ON) at the
 * decant seam: one compartment receives one SKU, and a tote never receives more distinct SKUs
 * than its type has compartments. OFF disables both checks.
 */
@SpringBootTest
@Testcontainers
class DecantCompartmentRuleTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    GtpStationService stationService;

    @Autowired
    WorkCycleService cycleService;

    @MockBean
    MasterDataClient masterData;

    @MockBean
    InventoryClient inventory;

    private GtpStation decantStation() {
        GtpStation station = stationService.createStation(new CreateStationRequest(
                UUID.randomUUID(), "GTP-" + UUID.randomUUID(), null, "ORDER_LOCATION",
                List.of("PICKING", "DECANTING"), List.of()));
        stationService.addNode(station.getId(), new AddNodeRequest("STOCK", "S1", null, null, null, 0));
        return station;
    }

    private static StartCycleRequest decant(UUID source, UUID target, StartCycleRequest.LineSpec... lines) {
        return new StartCycleRequest("DECANTING", null, source, target, null, null, List.of(lines));
    }

    private static StartCycleRequest.LineSpec move(UUID skuId, String compartment) {
        return new StartCycleRequest.LineSpec(null, skuId, compartment, BigDecimal.TEN, null);
    }

    @Test
    void rejectsTwoSkusIntoTheSameCompartment() {
        when(masterData.singleSkuPerCompartmentRule()).thenReturn(true);
        GtpStation station = decantStation();

        assertThatThrownBy(() -> cycleService.startCycle(station.getId(),
                decant(UUID.randomUUID(), UUID.randomUUID(),
                        move(UUID.randomUUID(), "C1"), move(UUID.randomUUID(), "C1"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one compartment holds one SKU");
    }

    @Test
    void rejectsMoreSkusThanTheTargetHuTypeHasCompartments() {
        when(masterData.singleSkuPerCompartmentRule()).thenReturn(true);
        UUID target = UUID.randomUUID();
        UUID huTypeId = UUID.randomUUID();
        when(inventory.huTypeOf(target)).thenReturn(Optional.of(huTypeId));
        when(masterData.compartmentsOfHuType(huTypeId)).thenReturn(Optional.of(1));
        GtpStation station = decantStation();

        assertThatThrownBy(() -> cycleService.startCycle(station.getId(),
                decant(UUID.randomUUID(), target,
                        move(UUID.randomUUID(), "C1"), move(UUID.randomUUID(), "C2"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 compartment");
    }

    @Test
    void allowsDistinctSkusInDistinctCompartmentsOfAMultiCompartmentTote() {
        when(masterData.singleSkuPerCompartmentRule()).thenReturn(true);
        UUID target = UUID.randomUUID();
        UUID huTypeId = UUID.randomUUID();
        when(inventory.huTypeOf(target)).thenReturn(Optional.of(huTypeId));
        when(masterData.compartmentsOfHuType(huTypeId)).thenReturn(Optional.of(4));
        GtpStation station = decantStation();

        WorkCycle cycle = cycleService.startCycle(station.getId(),
                decant(UUID.randomUUID(), target,
                        move(UUID.randomUUID(), "C1"), move(UUID.randomUUID(), "C2")));

        assertThat(cycle.getOperatingMode()).isEqualTo("DECANTING");
    }

    @Test
    void ruleOffAllowsMixingIntoOneCompartment() {
        when(masterData.singleSkuPerCompartmentRule()).thenReturn(false);
        when(inventory.huTypeOf(any())).thenReturn(Optional.empty());
        GtpStation station = decantStation();

        WorkCycle cycle = cycleService.startCycle(station.getId(),
                decant(UUID.randomUUID(), UUID.randomUUID(),
                        move(UUID.randomUUID(), "C1"), move(UUID.randomUUID(), "C1")));

        assertThat(cycle.getOperatingMode()).isEqualTo("DECANTING");
    }

    @Test
    void unresolvableHuTypeStillEnforcesThePerCompartmentCheckOnly() {
        when(masterData.singleSkuPerCompartmentRule()).thenReturn(true);
        when(inventory.huTypeOf(any())).thenReturn(Optional.empty());
        GtpStation station = decantStation();

        // distinct compartments, HU type unknown: allowed (capacity check is skipped)
        WorkCycle cycle = cycleService.startCycle(station.getId(),
                decant(UUID.randomUUID(), UUID.randomUUID(),
                        move(UUID.randomUUID(), "C1"), move(UUID.randomUUID(), "C2")));
        assertThat(cycle.getOperatingMode()).isEqualTo("DECANTING");
    }
}
