package org.openwcs.gtp;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.gtp.api.AddNodeRequest;
import org.openwcs.gtp.api.ConfirmPutRequest;
import org.openwcs.gtp.api.CreateStationRequest;
import org.openwcs.gtp.api.OpenDestinationRequest;
import org.openwcs.gtp.api.PresentStockRequest;
import org.openwcs.gtp.domain.DestinationDemand;
import org.openwcs.gtp.domain.GtpStation;
import org.openwcs.gtp.domain.PutInstruction;
import org.openwcs.gtp.domain.StationNode;
import org.openwcs.gtp.domain.WorkCycle;
import org.openwcs.gtp.repo.DestinationDemandRepository;
import org.openwcs.gtp.repo.PutInstructionRepository;
import org.openwcs.gtp.repo.WorkCycleRepository;
import org.openwcs.gtp.service.GtpStationService;
import org.openwcs.gtp.service.WorkCycleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end goods-to-person execution against PostgreSQL 16: a single presented stock HU serves
 * many order destinations (the batch), confirmations decrement stock and complete destinations,
 * and BOTH modes (ORDER_LOCATION, PUT_WALL) produce correct put instructions.
 */
@SpringBootTest
@Testcontainers
class WorkCycleExecutionTest {

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

    @Autowired
    WorkCycleRepository cycles;

    @Autowired
    DestinationDemandRepository demands;

    @Autowired
    PutInstructionRepository puts;

    @Test
    void oneStockHuServesManyDestinationsAndConfirmationsComplete() {
        UUID sku = UUID.randomUUID();
        GtpStation station = createStation("ORDER_LOCATION", UUID.randomUUID());
        addStock(station, "S1");
        StationNode a = addOrder(station, "A1", "light-A1");
        StationNode b = addOrder(station, "B1", "light-B1");
        StationNode c = addOrder(station, "C1", "light-C1");

        // Three orders need the same SKU at three destinations: 2 + 3 + 1 = 6 total.
        DestinationDemand dA = openDemand(a, "ORD-A", sku, 2);
        DestinationDemand dB = openDemand(b, "ORD-B", sku, 3);
        DestinationDemand dC = openDemand(c, "ORD-C", sku, 1);

        // Present a stock HU with exactly enough (6) — the batch should fan out to all three.
        WorkCycle cycle = cycleService.present(station.getId(),
                new PresentStockRequest(null, UUID.randomUUID(), sku, new BigDecimal("6")));

        List<PutInstruction> list = puts.findByWorkCycleId(cycle.getId());
        assertThat(list).hasSize(3);
        assertThat(list).allMatch(p -> p.getOrderHuId() != null && p.getPutLightId() != null);
        assertThat(list.stream().map(p -> p.getQty().intValueExact()).sorted().toList())
                .containsExactly(1, 2, 3);

        // Confirm every put (full qty) — destinations complete, cycle remaining hits zero.
        for (PutInstruction p : list) {
            cycleService.confirm(p.getId(), new ConfirmPutRequest(null));
        }

        assertThat(demands.findById(dA.getId()).orElseThrow().getStatus()).isEqualTo("COMPLETED");
        assertThat(demands.findById(dB.getId()).orElseThrow().getStatus()).isEqualTo("COMPLETED");
        assertThat(demands.findById(dC.getId()).orElseThrow().getStatus()).isEqualTo("COMPLETED");

        WorkCycle done = cycles.findById(cycle.getId()).orElseThrow();
        assertThat(done.getRemainingQty()).isEqualByComparingTo("0");
        assertThat(done.getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void shortStockHuLeavesSurplusDemandOpen() {
        UUID sku = UUID.randomUUID();
        GtpStation station = createStation("ORDER_LOCATION", UUID.randomUUID());
        addStock(station, "S1");
        StationNode a = addOrder(station, "A1", "light-A1");
        StationNode b = addOrder(station, "B1", "light-B1");

        openDemand(a, "ORD-A", sku, 5);          // most-needed first
        DestinationDemand dB = openDemand(b, "ORD-B", sku, 4);

        // Only 6 available against 9 of demand: greedy fills the 5-shortfall, then 1 of the 4.
        WorkCycle cycle = cycleService.present(station.getId(),
                new PresentStockRequest(null, UUID.randomUUID(), sku, new BigDecimal("6")));

        List<PutInstruction> list = puts.findByWorkCycleId(cycle.getId());
        assertThat(list.stream().map(p -> p.getQty().intValueExact()).reduce(0, Integer::sum))
                .isEqualTo(6);
        for (PutInstruction p : list) {
            cycleService.confirm(p.getId(), new ConfirmPutRequest(null));
        }

        // B still needs 3 — its demand stays OPEN for the next stock HU.
        DestinationDemand bAfter = demands.findById(dB.getId()).orElseThrow();
        assertThat(bAfter.getStatus()).isEqualTo("OPEN");
        assertThat(bAfter.remaining()).isEqualByComparingTo("3");
    }

    @Test
    void shortConfirmClosesInstructionShortAndKeepsDemandOpen() {
        UUID sku = UUID.randomUUID();
        GtpStation station = createStation("ORDER_LOCATION", UUID.randomUUID());
        addStock(station, "S1");
        StationNode a = addOrder(station, "A1", "light-A1");
        DestinationDemand dA = openDemand(a, "ORD-A", sku, 4);

        WorkCycle cycle = cycleService.present(station.getId(),
                new PresentStockRequest(null, UUID.randomUUID(), sku, new BigDecimal("4")));
        PutInstruction p = puts.findByWorkCycleId(cycle.getId()).get(0);

        // Operator only finds 3 in the HU.
        PutInstruction confirmed = cycleService.confirm(p.getId(),
                new ConfirmPutRequest(new BigDecimal("3")));
        assertThat(confirmed.getStatus()).isEqualTo("SHORT");

        DestinationDemand after = demands.findById(dA.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("OPEN");
        assertThat(after.remaining()).isEqualByComparingTo("1");
    }

    @Test
    void putWallModeProducesLitRackInstructions() {
        UUID sku = UUID.randomUUID();
        GtpStation station = createStation("PUT_WALL", UUID.randomUUID());
        addStock(station, "S1");
        StationNode cubby1 = addOrder(station, "R1C1", "putlight-1");
        StationNode cubby2 = addOrder(station, "R1C2", "putlight-2");
        openDemand(cubby1, "ORD-A", sku, 2);
        openDemand(cubby2, "ORD-B", sku, 3);

        WorkCycle cycle = cycleService.present(station.getId(),
                new PresentStockRequest(null, UUID.randomUUID(), sku, new BigDecimal("5")));

        assertThat(cycleService.modeOf(station.getId())).isEqualTo("PUT_WALL");
        List<PutInstruction> list = puts.findByWorkCycleId(cycle.getId());
        assertThat(list).hasSize(2);
        // PUT_WALL: each lit cubby has its put-light + qty.
        assertThat(list).allMatch(p -> p.getPutLightId() != null && p.getQty().signum() > 0);
        assertThat(list.stream().map(PutInstruction::getPutLightId).sorted().toList())
                .containsExactly("putlight-1", "putlight-2");
    }

    // --- helpers ---

    private GtpStation createStation(String mode, UUID warehouseId) {
        return stationService.createStation(new CreateStationRequest(
                warehouseId, "GTP-" + UUID.randomUUID(), mode, List.of()));
    }

    private void addStock(GtpStation station, String code) {
        stationService.addNode(station.getId(),
                new AddNodeRequest("STOCK", code, null, null, null, 0));
    }

    private StationNode addOrder(GtpStation station, String code, String light) {
        return stationService.addNode(station.getId(),
                new AddNodeRequest("ORDER", code, light, null, null, 1));
    }

    private DestinationDemand openDemand(StationNode node, String orderRef, UUID sku, int qty) {
        return stationService.openDestination(node.getId(),
                new OpenDestinationRequest(UUID.randomUUID(), orderRef, UUID.randomUUID(), sku,
                        new BigDecimal(qty)));
    }
}
