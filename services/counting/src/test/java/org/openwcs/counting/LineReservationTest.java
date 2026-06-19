package org.openwcs.counting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
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
import org.openwcs.counting.repo.CountLineRepository;
import org.openwcs.counting.service.ClaimNextResult;
import org.openwcs.counting.service.CountTaskScope;
import org.openwcs.counting.service.CountingService;
import org.openwcs.counting.service.CreateCountTaskCommand;
import org.openwcs.counting.service.LineReservationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots counting against PostgreSQL with every outbound client mocked, and exercises per-operator
 * line reservation: claim-next reserves exactly one eligible line and a second claim returns a
 * DIFFERENT line (FOR UPDATE SKIP LOCKED skips the reserved one); claim is scoped to the area subtree
 * the mocked master-data client returns; release frees only not-yet-started reservations; and the
 * sweeper reclaims stale unstarted reservations while leaving started ones alone.
 */
@SpringBootTest
@Testcontainers
class LineReservationTest {

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

    @Autowired
    LineReservationService reservations;

    @Autowired
    CountLineRepository lineRepo;

    private CreateCountTaskCommand task(UUID wh, List<CountTaskScope> cells) {
        return new CreateCountTaskCommand(
                wh, "ZONE", null, "BLIND", "AD_HOC", null, null, BigDecimal.ZERO, null, cells);
    }

    @Test
    void claimNextReservesOneLineAndSecondClaimSkipsIt() {
        UUID wh = UUID.randomUUID();
        UUID area = UUID.randomUUID();
        UUID locA = UUID.randomUUID();
        UUID locB = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        when(inventory.expectedOnHand(org.mockito.ArgumentMatchers.eq(wh),
                org.mockito.ArgumentMatchers.eq(sku), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new BigDecimal("3"));
        when(masterData.areaLocationIds(area)).thenReturn(List.of(locA, locB));

        CountTask created = counting.generate(task(wh, List.of(
                new CountTaskScope(locA, sku, null, "EACH"),
                new CountTaskScope(locB, sku, null, "EACH"))));
        assertThat(counting.rawLines(created.getId())).hasSize(2);

        ClaimNextResult first = reservations.claimNext(wh, area, "device-1", "op1");
        assertThat(first.found()).isTrue();
        assertThat(first.countType()).isEqualTo("BLIND");
        assertThat(first.expectedQty()).isEqualByComparingTo("3");

        // A second claim must get a DIFFERENT line: the first is reserved (reserved_by NOT NULL), so the
        // candidate query skips it.
        ClaimNextResult second = reservations.claimNext(wh, area, "device-2", "op2");
        assertThat(second.found()).isTrue();
        assertThat(second.lineId()).isNotEqualTo(first.lineId());

        // Both lines are now reserved, the third claim finds nothing.
        ClaimNextResult third = reservations.claimNext(wh, area, "device-3", "op3");
        assertThat(third.found()).isFalse();

        CountLine reservedFirst = lineRepo.findById(first.lineId()).orElseThrow();
        assertThat(reservedFirst.getReservedBy()).isEqualTo("op1");
        assertThat(reservedFirst.getReservedByInstance()).isEqualTo("device-1");
        assertThat(reservedFirst.getReservedAt()).isNotNull();
        assertThat(reservedFirst.getStartedAt()).isNull();
    }

    @Test
    void claimNextRespectsAreaSubtree() {
        UUID wh = UUID.randomUUID();
        UUID area = UUID.randomUUID();
        UUID inArea = UUID.randomUUID();
        UUID outOfArea = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        when(inventory.expectedOnHand(org.mockito.ArgumentMatchers.eq(wh),
                org.mockito.ArgumentMatchers.eq(sku), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new BigDecimal("1"));
        // The area resolves only to inArea; the out-of-area line must never be claimed.
        when(masterData.areaLocationIds(area)).thenReturn(List.of(inArea));

        counting.generate(task(wh, List.of(
                new CountTaskScope(inArea, sku, null, "EACH"),
                new CountTaskScope(outOfArea, sku, null, "EACH"))));

        ClaimNextResult claim = reservations.claimNext(wh, area, "device-1", "op1");
        assertThat(claim.found()).isTrue();
        assertThat(claim.locationId()).isEqualTo(inArea);

        // No more eligible lines inside the area: the out-of-area line is not reachable.
        assertThat(reservations.claimNext(wh, area, "device-1", "op1").found()).isFalse();
    }

    @Test
    void emptyAreaReturnsNotFound() {
        UUID wh = UUID.randomUUID();
        UUID area = UUID.randomUUID();
        when(masterData.areaLocationIds(area)).thenReturn(List.of());

        assertThat(reservations.claimNext(wh, area, "device-1", "op1").found()).isFalse();
    }

    @Test
    void releaseFreesOnlyNotStartedReservations() {
        UUID wh = UUID.randomUUID();
        UUID area = UUID.randomUUID();
        UUID locA = UUID.randomUUID();
        UUID locB = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        when(inventory.expectedOnHand(org.mockito.ArgumentMatchers.eq(wh),
                org.mockito.ArgumentMatchers.eq(sku), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new BigDecimal("5"));
        when(masterData.areaLocationIds(area)).thenReturn(List.of(locA, locB));

        CountTask created = counting.generate(task(wh, List.of(
                new CountTaskScope(locA, sku, null, "EACH"),
                new CountTaskScope(locB, sku, null, "EACH"))));

        // Unique instance id so this test's release does not collide with reservations left by other
        // tests sharing the container DB.
        String instance = "device-" + UUID.randomUUID();
        ClaimNextResult a = reservations.claimNext(wh, area, instance, "op1");
        ClaimNextResult b = reservations.claimNext(wh, area, instance, "op1");
        assertThat(a.found()).isTrue();
        assertThat(b.found()).isTrue();

        // Operator starts counting one of the two reserved lines (station count): it becomes started.
        counting.recordStationCount(created.getId(), a.lineId(), new BigDecimal("5"), "op1");
        assertThat(lineRepo.findById(a.lineId()).orElseThrow().getStartedAt()).isNotNull();

        // Release the instance: only the unstarted line (b) is freed; the started line (a) stays.
        int freed = reservations.release(instance);
        assertThat(freed).isEqualTo(1);

        assertThat(lineRepo.findById(b.lineId()).orElseThrow().getReservedBy()).isNull();
        // a was started (and reached a terminal station state); its reservation is left in place.
        assertThat(lineRepo.findById(a.lineId()).orElseThrow().getStartedAt()).isNotNull();

        // Release is idempotent: a second call frees nothing more.
        assertThat(reservations.release(instance)).isZero();
    }

    @Test
    @Transactional
    void sweeperReclaimsStaleUnstartedAndSkipsStarted() {
        UUID wh = UUID.randomUUID();
        UUID locStale = UUID.randomUUID();
        UUID locStarted = UUID.randomUUID();
        UUID locFresh = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        when(inventory.expectedOnHand(org.mockito.ArgumentMatchers.eq(wh),
                org.mockito.ArgumentMatchers.eq(sku), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new BigDecimal("2"));

        CountTask created = counting.generate(task(wh, List.of(
                new CountTaskScope(locStale, sku, null, "EACH"),
                new CountTaskScope(locStarted, sku, null, "EACH"),
                new CountTaskScope(locFresh, sku, null, "EACH"))));
        List<CountLine> lines = counting.rawLines(created.getId());
        CountLine stale = lineAt(lines, locStale);
        CountLine started = lineAt(lines, locStarted);
        CountLine fresh = lineAt(lines, locFresh);

        Instant old = Instant.now().minusSeconds(3600); // an hour ago, well past the 15m TTL
        Instant recent = Instant.now().minusSeconds(30); // within the TTL

        // Stale: reserved an hour ago, never started -> must be reclaimed.
        stale.setReservedBy("op1");
        stale.setReservedByInstance("device-1");
        stale.setReservedAt(old);

        // Started: reserved an hour ago but counting started -> must be left alone.
        started.setReservedBy("op1");
        started.setReservedByInstance("device-1");
        started.setReservedAt(old);
        started.setStartedAt(Instant.now());

        // Fresh: reserved recently, never started -> within TTL, must be left alone.
        fresh.setReservedBy("op2");
        fresh.setReservedByInstance("device-2");
        fresh.setReservedAt(recent);

        lineRepo.saveAll(List.of(stale, started, fresh));
        lineRepo.flush();

        int freed = reservations.sweepStale();
        assertThat(freed).isEqualTo(1);

        assertThat(lineRepo.findById(stale.getId()).orElseThrow().getReservedBy()).isNull();
        assertThat(lineRepo.findById(started.getId()).orElseThrow().getReservedBy()).isEqualTo("op1");
        assertThat(lineRepo.findById(fresh.getId()).orElseThrow().getReservedBy()).isEqualTo("op2");
    }

    private static CountLine lineAt(List<CountLine> lines, UUID locationId) {
        return lines.stream().filter(l -> l.getLocationId().equals(locationId)).findFirst().orElseThrow();
    }
}
