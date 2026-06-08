package org.openwcs.counting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.counting.client.FlowClient;
import org.openwcs.counting.client.GtpClient;
import org.openwcs.counting.client.InventoryClient;
import org.openwcs.counting.client.MasterDataClient;
import org.openwcs.counting.client.TxLogClient;
import org.openwcs.counting.domain.CountLine;
import org.openwcs.counting.domain.CountTask;
import org.openwcs.counting.repo.CountTaskRepository;
import org.openwcs.counting.service.CountEntry;
import org.openwcs.counting.service.CountLineView;
import org.openwcs.counting.service.CountTaskScope;
import org.openwcs.counting.service.CountingService;
import org.openwcs.counting.service.CreateCountTaskCommand;
import org.openwcs.counting.service.ReconciliationResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots the counting service against PostgreSQL 16 (Flyway V1 then Hibernate {@code validate}) with
 * the inventory + txlog clients mocked. Covers: generating a task snapshots the expected qty;
 * submitting a count computes the variance; reconcile auto-approves within tolerance and emits a
 * StockAdjusted adjustment intent; out-of-tolerance spawns a recount task and posts no adjustment;
 * and blind vs variance capture (expected qty hidden for a blind count).
 */
@SpringBootTest
@Testcontainers
class CountingServiceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockBean
    InventoryClient inventory;

    @MockBean
    TxLogClient txlog;

    @MockBean
    MasterDataClient masterData;

    @MockBean
    GtpClient gtp;

    @MockBean
    FlowClient flow;

    @Autowired
    CountingService counting;

    private CreateCountTaskCommand task(UUID wh, UUID loc, UUID sku, String countType, BigDecimal tolerance) {
        return new CreateCountTaskCommand(
                wh, "LOCATION", loc, countType, "AD_HOC", null, null, tolerance, null,
                List.of(new CountTaskScope(loc, sku, null, "EACH")));
    }

    @Test
    void deletesOpenTaskButRejectsActiveOne() {
        UUID wh = UUID.randomUUID();
        UUID loc = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        when(inventory.expectedOnHand(wh, sku, loc)).thenReturn(new BigDecimal("5"));

        // An OPEN task can be deleted.
        CountTask open = counting.generate(task(wh, loc, sku, "BLIND", BigDecimal.ZERO));
        counting.deleteTask(open.getId());
        assertThatThrownBy(() -> counting.task(open.getId())).isInstanceOf(RuntimeException.class);

        // Once counted (no longer OPEN) it cannot be deleted.
        CountTask counted = counting.generate(task(wh, loc, sku, "BLIND", BigDecimal.ZERO));
        CountLine line = counting.rawLines(counted.getId()).get(0);
        counting.submitCounts(counted.getId(), List.of(new CountEntry(line.getId(), new BigDecimal("5"))), "op1");
        assertThatThrownBy(() -> counting.deleteTask(counted.getId())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void withinToleranceVarianceAutoApprovesAndPostsAdjustment() {
        UUID wh = UUID.randomUUID();
        UUID loc = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        // Inventory says 10 expected; tolerance 2.
        when(inventory.expectedOnHand(wh, sku, loc)).thenReturn(new BigDecimal("10"));
        when(txlog.postStockAdjusted(any())).thenReturn(eventId);

        CountTask created = counting.generate(task(wh, loc, sku, "VARIANCE", new BigDecimal("2")));
        assertThat(created.getStatus()).isEqualTo("OPEN");

        List<CountLine> lines = counting.rawLines(created.getId());
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).getExpectedQty()).isEqualByComparingTo("10");

        // Operator counts 9 -> variance -1, within tolerance 2 -> auto-approve + adjustment.
        counting.submitCounts(created.getId(),
                List.of(new CountEntry(lines.get(0).getId(), new BigDecimal("9"))), "op1");

        ReconciliationResult result = counting.reconcile(created.getId(), "sup1");

        assertThat(result.status()).isEqualTo("RECONCILED");
        assertThat(result.approvedLines()).isEqualTo(1);
        assertThat(result.adjustedLines()).isEqualTo(1);
        assertThat(result.recountLines()).isZero();
        assertThat(result.recountTaskId()).isNull();

        // The adjustment delta is the variance (counted - expected = -1).
        org.mockito.ArgumentCaptor<TxLogClient.StockAdjustment> captor =
                org.mockito.ArgumentCaptor.forClass(TxLogClient.StockAdjustment.class);
        verify(txlog).postStockAdjusted(captor.capture());
        assertThat(captor.getValue().qtyDelta()).isEqualByComparingTo("-1");
        assertThat(captor.getValue().actor()).isEqualTo("sup1");

        CountLine reconciled = counting.rawLines(created.getId()).get(0);
        assertThat(reconciled.getStatus()).isEqualTo("ADJUSTED");
        assertThat(reconciled.getAdjustmentEventId()).isEqualTo(eventId);
    }

    @Test
    void outOfToleranceVarianceSpawnsRecountAndPostsNoAdjustment() {
        UUID wh = UUID.randomUUID();
        UUID loc = UUID.randomUUID();
        UUID sku = UUID.randomUUID();

        when(inventory.expectedOnHand(wh, sku, loc)).thenReturn(new BigDecimal("10"));

        // Zero tolerance: a variance of -3 is out of tolerance.
        CountTask created = counting.generate(task(wh, loc, sku, "VARIANCE", BigDecimal.ZERO));
        UUID lineId = counting.rawLines(created.getId()).get(0).getId();

        counting.submitCounts(created.getId(),
                List.of(new CountEntry(lineId, new BigDecimal("7"))), "op1");

        ReconciliationResult result = counting.reconcile(created.getId(), "sup1");

        assertThat(result.status()).isEqualTo("RECOUNT");
        assertThat(result.recountLines()).isEqualTo(1);
        assertThat(result.adjustedLines()).isZero();
        assertThat(result.recountTaskId()).isNotNull();

        // No inventory adjustment for an out-of-tolerance variance — it must be recounted first.
        verify(txlog, never()).postStockAdjusted(any());

        // A follow-up RECOUNT task was created for the same cell, OPEN and linked to the parent.
        CountTask recount = counting.task(result.recountTaskId());
        assertThat(recount.getOrigin()).isEqualTo("RECOUNT");
        assertThat(recount.getStatus()).isEqualTo("OPEN");
        assertThat(recount.getParentTaskId()).isEqualTo(created.getId());
        assertThat(counting.rawLines(recount.getId())).hasSize(1);
    }

    @Test
    void blindCountHidesExpectedQtyVarianceShowsIt() {
        UUID wh = UUID.randomUUID();
        UUID loc = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        when(inventory.expectedOnHand(wh, sku, loc)).thenReturn(new BigDecimal("10"));

        CountTask blind = counting.generate(task(wh, loc, sku, "BLIND", BigDecimal.ZERO));
        CountLineView blindLine = counting.linesFor(blind.getId()).get(0);
        // Operator on a blind count must not see the expected qty.
        assertThat(blindLine.expectedQty()).isNull();

        CountTask variance = counting.generate(task(wh, loc, sku, "VARIANCE", BigDecimal.ZERO));
        CountLineView varLine = counting.linesFor(variance.getId()).get(0);
        assertThat(varLine.expectedQty()).isEqualByComparingTo("10");
    }
}
