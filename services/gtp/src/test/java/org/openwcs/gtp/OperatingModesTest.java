package org.openwcs.gtp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.gtp.api.AddNodeRequest;
import org.openwcs.gtp.api.CreateStationRequest;
import org.openwcs.gtp.api.StartCycleRequest;
import org.openwcs.gtp.api.SubmitOutcomeRequest;
import org.openwcs.gtp.domain.GtpStation;
import org.openwcs.gtp.domain.TaskLine;
import org.openwcs.gtp.domain.WorkCycle;
import org.openwcs.gtp.repo.TaskLineRepository;
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
 * Operating modes (ADR 0006 "Operating modes") against PostgreSQL 16: a cycle in each of the four
 * non-PICKING modes produces the right task lines and records outcomes — decant moves source→target,
 * stock-count computes variance, QC records PASS/FAIL/HOLD, maintenance records OK/DEFECTIVE/REPAIR —
 * plus supported-mode gating and the documented integration seams. The PICKING flow is covered by
 * {@link WorkCycleExecutionTest}, which still passes unchanged.
 */
@SpringBootTest
@Testcontainers
class OperatingModesTest {

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
    TaskLineRepository taskLines;

    @Test
    void decantingMovesSourceIntoTargetCompartmentsAndExposesPutawaySeam() {
        GtpStation station = station("DECANTING");
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID skuX = UUID.randomUUID();
        UUID skuY = UUID.randomUUID();

        WorkCycle cycle = cycleService.startCycle(station.getId(), new StartCycleRequest(
                "DECANTING", null, source, target, null, null,
                List.of(new StartCycleRequest.LineSpec(null, skuX, "C1", new BigDecimal("4"), null),
                        new StartCycleRequest.LineSpec(null, skuY, "C2", new BigDecimal("6"), null))));

        assertThat(cycle.getOperatingMode()).isEqualTo("DECANTING");
        assertThat(cycle.getStockHuId()).isEqualTo(source);
        assertThat(cycle.getTargetHuId()).isEqualTo(target);

        List<TaskLine> lines = taskLines.findByWorkCycleId(cycle.getId());
        assertThat(lines).hasSize(2);
        assertThat(lines).allMatch(l -> "DECANT_MOVE".equals(l.getLineType())
                && target.equals(l.getHuId()) && "OPEN".equals(l.getStatus()));

        // Confirm both moves (full requested qty for one, a short move for the other).
        TaskLine first = lines.stream().filter(l -> "C1".equals(l.getCompartment())).findFirst().orElseThrow();
        TaskLine second = lines.stream().filter(l -> "C2".equals(l.getCompartment())).findFirst().orElseThrow();
        cycleService.submitOutcome(first.getId(), new SubmitOutcomeRequest(null, null)); // full
        cycleService.submitOutcome(second.getId(), new SubmitOutcomeRequest(new BigDecimal("5"), null));

        assertThat(taskLines.findById(first.getId()).orElseThrow().getActualQty()).isEqualByComparingTo("4");
        assertThat(taskLines.findById(second.getId()).orElseThrow().getActualQty()).isEqualByComparingTo("5");

        // Cycle completes; the filled target + its SKUs are exposed for slotting put-away (seam).
        assertThat(cycleService.requireCycle(cycle.getId()).getStatus()).isEqualTo("COMPLETED");
        WorkCycleService.DecantedTarget ready = cycleService.decantedTargetReady(cycle.getId());
        assertThat(ready.targetHuId()).isEqualTo(target);
        assertThat(ready.skuIds()).containsExactlyInAnyOrder(skuX, skuY);
    }

    @Test
    void stockCountComputesVarianceAndExposesAdjustmentSeam() {
        GtpStation station = station("STOCK_COUNT");
        UUID hu = UUID.randomUUID();
        UUID skuOver = UUID.randomUUID();
        UUID skuExact = UUID.randomUUID();

        WorkCycle cycle = cycleService.startCycle(station.getId(), new StartCycleRequest(
                "STOCK_COUNT", null, hu, null, null, null,
                List.of(new StartCycleRequest.LineSpec(null, skuOver, null, new BigDecimal("10"), null),
                        new StartCycleRequest.LineSpec(null, skuExact, null, new BigDecimal("5"), null))));

        List<TaskLine> lines = taskLines.findByWorkCycleId(cycle.getId());
        assertThat(lines).hasSize(2).allMatch(l -> "COUNT_ENTRY".equals(l.getLineType()));

        TaskLine over = lines.stream().filter(l -> skuOver.equals(l.getSkuId())).findFirst().orElseThrow();
        TaskLine exact = lines.stream().filter(l -> skuExact.equals(l.getSkuId())).findFirst().orElseThrow();

        // Counted 8 of an expected 10 (variance -2); counted 5 of 5 (variance 0).
        cycleService.submitOutcome(over.getId(), new SubmitOutcomeRequest(new BigDecimal("8"), null));
        cycleService.submitOutcome(exact.getId(), new SubmitOutcomeRequest(new BigDecimal("5"), null));

        assertThat(taskLines.findById(over.getId()).orElseThrow().getVariance()).isEqualByComparingTo("-2");
        assertThat(taskLines.findById(exact.getId()).orElseThrow().getVariance()).isEqualByComparingTo("0");

        // Only the non-zero variance surfaces as a StockAdjusted intent (seam).
        List<WorkCycleService.CountAdjustment> adjustments =
                cycleService.stockCountAdjustment(cycle.getId());
        assertThat(adjustments).hasSize(1);
        assertThat(adjustments.get(0).skuId()).isEqualTo(skuOver);
        assertThat(adjustments.get(0).variance()).isEqualByComparingTo("-2");
    }

    @Test
    void qcRecordsPassFailHoldVerdicts() {
        GtpStation station = station("QC");
        UUID hu = UUID.randomUUID();
        UUID skuPass = UUID.randomUUID();
        UUID skuFail = UUID.randomUUID();
        UUID skuHold = UUID.randomUUID();

        WorkCycle cycle = cycleService.startCycle(station.getId(), new StartCycleRequest(
                "QC", null, hu, null, null, null,
                List.of(new StartCycleRequest.LineSpec(null, skuPass, null, null, null),
                        new StartCycleRequest.LineSpec(null, skuFail, null, null, null),
                        new StartCycleRequest.LineSpec(null, skuHold, null, null, null))));

        List<TaskLine> lines = taskLines.findByWorkCycleId(cycle.getId());
        assertThat(lines).hasSize(3).allMatch(l -> "QC_VERDICT".equals(l.getLineType()));

        record V(UUID sku, String verdict) {}
        for (V v : List.of(new V(skuPass, "PASS"), new V(skuFail, "FAIL"), new V(skuHold, "HOLD"))) {
            TaskLine line = lines.stream().filter(l -> v.sku().equals(l.getSkuId())).findFirst().orElseThrow();
            TaskLine confirmed = cycleService.submitOutcome(line.getId(), new SubmitOutcomeRequest(null, v.verdict()));
            assertThat(confirmed.getVerdict()).isEqualTo(v.verdict());
            assertThat(confirmed.getStatus()).isEqualTo("CONFIRMED");
        }
        assertThat(cycleService.requireCycle(cycle.getId()).getStatus()).isEqualTo("COMPLETED");

        // An out-of-range verdict (a MAINTENANCE verdict on a QC line) is rejected.
        WorkCycle other = cycleService.startCycle(station.getId(), new StartCycleRequest(
                "QC", null, UUID.randomUUID(), null, null, null,
                List.of(new StartCycleRequest.LineSpec(null, UUID.randomUUID(), null, null, null))));
        TaskLine fresh = taskLines.findByWorkCycleId(other.getId()).get(0);
        assertThatThrownBy(() -> cycleService.submitOutcome(fresh.getId(), new SubmitOutcomeRequest(null, "OK")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void maintenanceRecordsConditionVerdicts() {
        GtpStation station = station("MAINTENANCE");
        UUID carrierOk = UUID.randomUUID();
        UUID carrierDefective = UUID.randomUUID();
        UUID carrierRepair = UUID.randomUUID();

        WorkCycle cycle = cycleService.startCycle(station.getId(), new StartCycleRequest(
                "MAINTENANCE", null, carrierOk, null, null, null,
                List.of(new StartCycleRequest.LineSpec(carrierOk, null, null, null, null),
                        new StartCycleRequest.LineSpec(carrierDefective, null, null, null, null),
                        new StartCycleRequest.LineSpec(carrierRepair, null, null, null, null))));

        List<TaskLine> lines = taskLines.findByWorkCycleId(cycle.getId());
        assertThat(lines).hasSize(3).allMatch(l -> "MAINTENANCE_CHECK".equals(l.getLineType()));

        record V(UUID hu, String verdict) {}
        for (V v : List.of(new V(carrierOk, "OK"), new V(carrierDefective, "DEFECTIVE"),
                new V(carrierRepair, "REPAIR"))) {
            TaskLine line = lines.stream().filter(l -> v.hu().equals(l.getHuId())).findFirst().orElseThrow();
            TaskLine confirmed = cycleService.submitOutcome(line.getId(), new SubmitOutcomeRequest(null, v.verdict()));
            assertThat(confirmed.getVerdict()).isEqualTo(v.verdict());
        }
        assertThat(cycleService.requireCycle(cycle.getId()).getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void stationRejectsAnUnsupportedOperatingMode() {
        GtpStation pickOnly = stationService.createStation(new CreateStationRequest(
                UUID.randomUUID(), "GTP-" + UUID.randomUUID(), "ORDER_LOCATION", List.of("PICKING"), List.of()));
        addStock(pickOnly);

        assertThatThrownBy(() -> cycleService.startCycle(pickOnly.getId(), new StartCycleRequest(
                "QC", null, UUID.randomUUID(), null, null, null,
                List.of(new StartCycleRequest.LineSpec(null, UUID.randomUUID(), null, null, null)))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void setSupportedModesAlwaysRetainsPicking() {
        GtpStation station = stationService.createStation(new CreateStationRequest(
                UUID.randomUUID(), "GTP-" + UUID.randomUUID(), "PUT_WALL", List.of("PICKING"), List.of()));

        GtpStation updated = stationService.setSupportedModes(station.getId(), List.of("QC", "MAINTENANCE"));
        assertThat(updated.supportedModeSet())
                .extracting(Enum::name)
                .contains("PICKING", "QC", "MAINTENANCE");
    }

    // --- helpers ---

    private GtpStation station(String operatingMode) {
        GtpStation station = stationService.createStation(new CreateStationRequest(
                UUID.randomUUID(), "GTP-" + UUID.randomUUID(), "ORDER_LOCATION",
                List.of("PICKING", operatingMode), List.of()));
        addStock(station);
        return station;
    }

    private void addStock(GtpStation station) {
        stationService.addNode(station.getId(),
                new AddNodeRequest("STOCK", "S1", null, null, null, 0));
    }
}
