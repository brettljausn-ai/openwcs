package org.openwcs.gtp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.gtp.api.AddNodeRequest;
import org.openwcs.gtp.api.ConfirmPutRequest;
import org.openwcs.gtp.api.CreateStationRequest;
import org.openwcs.gtp.api.OpenDestinationRequest;
import org.openwcs.gtp.api.PresentStockRequest;
import org.openwcs.gtp.api.StationView;
import org.openwcs.gtp.api.WorkCycleView;
import org.openwcs.gtp.client.LightControlClient;
import org.openwcs.gtp.domain.GtpStation;
import org.openwcs.gtp.domain.PutInstruction;
import org.openwcs.gtp.domain.StationNode;
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
 * Configurable picking layout (configurable GTP picking) against PostgreSQL 16: pickLayout +
 * pickSlots validation and round-trip on the StationView and the runtime cycle view, plus
 * best-effort pick-to-light driving on present/confirm (illuminate then clear), including that a
 * light-client failure never fails the pick.
 */
@SpringBootTest
@Testcontainers
class PickLayoutTest {

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
    LightControlClient lights;

    // --- validation ---

    @Test
    void oneToNRequiresAtLeastTwoSlots() {
        UUID wh = UUID.randomUUID();
        // Missing slots is a 400 (IllegalArgumentException).
        assertThatThrownBy(() -> stationService.createStation(new CreateStationRequest(
                wh, "GTP-N0", null, "ORDER_LOCATION", List.of("PICKING"), "ONE_TO_N", null, List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pickSlots must be at least 2");
        // One slot is still a 400.
        assertThatThrownBy(() -> stationService.createStation(new CreateStationRequest(
                wh, "GTP-N1", null, "ORDER_LOCATION", List.of("PICKING"), "ONE_TO_N", 1, List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pickSlots must be at least 2");
    }

    @Test
    void putWallLayoutRequiresPutWallMode() {
        UUID wh = UUID.randomUUID();
        assertThatThrownBy(() -> stationService.createStation(new CreateStationRequest(
                wh, "GTP-PW", null, "ORDER_LOCATION", List.of("PICKING"), "PUT_WALL", null, List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PUT_WALL");
    }

    // --- round-trip ---

    @Test
    void pickLayoutAndSlotsRoundTripOnStationView() {
        GtpStation station = stationService.createStation(new CreateStationRequest(
                UUID.randomUUID(), "GTP-RT", null, "ORDER_LOCATION", List.of("PICKING"),
                "ONE_TO_N", 4, List.of()));

        StationView view = StationView.from(station, stationService.nodesOf(station.getId()));
        assertThat(view.pickLayout()).isEqualTo("ONE_TO_N");
        assertThat(view.pickSlots()).isEqualTo(4);

        // ONE_TO_ONE clears the slots.
        GtpStation reset = stationService.updateStation(station.getId(),
                new org.openwcs.gtp.api.UpdateStationRequest(
                        "GTP-RT", null, "ORDER_LOCATION", null, null, "ONE_TO_ONE", null));
        StationView resetView = StationView.from(reset, stationService.nodesOf(reset.getId()));
        assertThat(resetView.pickLayout()).isEqualTo("ONE_TO_ONE");
        assertThat(resetView.pickSlots()).isNull();
    }

    @Test
    void defaultsToOneToOneWhenOmitted() {
        GtpStation station = stationService.createStation(new CreateStationRequest(
                UUID.randomUUID(), "GTP-DEF", null, "ORDER_LOCATION", List.of("PICKING"),
                null, null, List.of()));
        assertThat(station.getPickLayout()).isEqualTo("ONE_TO_ONE");
        assertThat(station.getPickSlots()).isNull();
    }

    @Test
    void pickLayoutAndSlotsAreExposedOnTheCycleView() {
        UUID sku = UUID.randomUUID();
        GtpStation station = stationService.createStation(new CreateStationRequest(
                UUID.randomUUID(), "GTP-CV", null, "ORDER_LOCATION", List.of("PICKING"),
                "ONE_TO_N", 3, List.of()));
        addStock(station, "S1");
        StationNode a = addOrder(station, "A1", "light-A1");
        openDemand(a, "ORD-A", sku, 2);

        WorkCycle cycle = cycleService.present(station.getId(),
                new PresentStockRequest(null, UUID.randomUUID(), sku, new BigDecimal("2")));

        WorkCycleService.PickLayout layout = cycleService.pickLayoutOf(station.getId());
        WorkCycleView view = WorkCycleView.from(cycle, cycleService.modeOf(station.getId()),
                layout.pickLayout(), layout.pickSlots(),
                cycleService.putsOf(cycle.getId()), cycleService.taskLinesOf(cycle.getId()));
        assertThat(view.pickLayout()).isEqualTo("ONE_TO_N");
        assertThat(view.pickSlots()).isEqualTo(3);
    }

    // --- pick-to-light driving ---

    @Test
    void presentIlluminatesEachPutLightAndConfirmClearsIt() {
        UUID sku = UUID.randomUUID();
        GtpStation station = stationService.createStation(new CreateStationRequest(
                UUID.randomUUID(), "GTP-PTL", null, "ORDER_LOCATION", List.of("PICKING"),
                "ONE_TO_N", 2, List.of()));
        addStock(station, "S1");
        StationNode a = addOrder(station, "A1", "light-A1");
        StationNode b = addOrder(station, "B1", "light-B1");
        openDemand(a, "ORD-A", sku, 2);
        openDemand(b, "ORD-B", sku, 3);

        WorkCycle cycle = cycleService.present(station.getId(),
                new PresentStockRequest(null, UUID.randomUUID(), sku, new BigDecimal("5")));

        // present lit both put-lights with their qty.
        verify(lights).illuminate(eq(station.getWarehouseId()), eq("GTP-PTL"),
                eq("light-A1"), eq(new BigDecimal("2")));
        verify(lights).illuminate(eq(station.getWarehouseId()), eq("GTP-PTL"),
                eq("light-B1"), eq(new BigDecimal("3")));

        // confirm clears the light for that put.
        for (PutInstruction p : cycleService.putsOf(cycle.getId())) {
            cycleService.confirm(p.getId(), new ConfirmPutRequest(null));
        }
        verify(lights).clear(eq(station.getWarehouseId()), eq("GTP-PTL"), eq("light-A1"));
        verify(lights).clear(eq(station.getWarehouseId()), eq("GTP-PTL"), eq("light-B1"));
    }

    @Test
    void lightClientFailureDoesNotFailPresentOrConfirm() {
        doThrow(new RuntimeException("PTL offline"))
                .when(lights).illuminate(any(), any(), any(), any());
        doThrow(new RuntimeException("PTL offline"))
                .when(lights).clear(any(), any(), any());

        UUID sku = UUID.randomUUID();
        GtpStation station = stationService.createStation(new CreateStationRequest(
                UUID.randomUUID(), "GTP-PTLERR", null, "ORDER_LOCATION", List.of("PICKING"),
                null, null, List.of()));
        addStock(station, "S1");
        StationNode a = addOrder(station, "A1", "light-A1");
        openDemand(a, "ORD-A", sku, 2);

        // present must still build the put-list despite the light failure.
        WorkCycle cycle = cycleService.present(station.getId(),
                new PresentStockRequest(null, UUID.randomUUID(), sku, new BigDecimal("2")));
        List<PutInstruction> list = cycleService.putsOf(cycle.getId());
        assertThat(list).hasSize(1);
        verify(lights, times(1)).illuminate(any(), any(), any(), any());

        // confirm must still succeed despite the clear failure.
        PutInstruction confirmed = cycleService.confirm(list.get(0).getId(), new ConfirmPutRequest(null));
        assertThat(confirmed.getStatus()).isEqualTo("CONFIRMED");
    }

    // --- helpers ---

    private void addStock(GtpStation station, String code) {
        stationService.addNode(station.getId(), new AddNodeRequest("STOCK", code, null, null, null, 0));
    }

    private StationNode addOrder(GtpStation station, String code, String light) {
        return stationService.addNode(station.getId(),
                new AddNodeRequest("ORDER", code, light, null, null, 1));
    }

    private void openDemand(StationNode node, String orderRef, UUID sku, int qty) {
        stationService.openDestination(node.getId(),
                new OpenDestinationRequest(UUID.randomUUID(), orderRef, UUID.randomUUID(), sku,
                        new BigDecimal(qty)));
    }
}
