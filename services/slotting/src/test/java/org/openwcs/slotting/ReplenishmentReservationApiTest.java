package org.openwcs.slotting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.slotting.client.InventoryClient;
import org.openwcs.slotting.client.MasterDataClient;
import org.openwcs.slotting.domain.ReplenishmentTask;
import org.openwcs.slotting.repo.ReplenishmentTaskRepository;
import org.openwcs.slotting.service.ReplenishmentReservationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Per-operator replenishment reservation (Master Data Areas epic): claim-next reserves exactly one
 * task and is area-scoped (by destination pick face); EMERGENCY is claimed first; concurrent claims
 * get different tasks (FOR UPDATE SKIP LOCKED); release frees only not-yet-started reservations; the
 * sweeper frees stale ones but skips started. The master-data area lookup is mocked.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ReplenishmentReservationApiTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ReplenishmentTaskRepository tasks;

    @Autowired
    ReplenishmentReservationService reservations;

    @MockBean
    MasterDataClient masterData;

    @MockBean
    InventoryClient inventory;

    private ReplenishmentTask task(UUID wh, UUID toLocation, String priority, Instant createdAt) {
        ReplenishmentTask t = new ReplenishmentTask();
        t.setWarehouseId(wh);
        t.setSkuId(UUID.randomUUID());
        t.setUomId(UUID.randomUUID());
        t.setToLocationId(toLocation);
        t.setQty(new BigDecimal("10"));
        t.setPriority(priority);
        t.setTriggerType("BELOW_MIN");
        t.setStatus("PLANNED");
        ReplenishmentTask saved = tasks.save(t);
        if (createdAt != null) {
            // @CreationTimestamp stamps now; override so created_at ordering is deterministic.
            jdbc.update("update slotting.replenishment_task set created_at = ? where replenishment_task_id = ?",
                    java.sql.Timestamp.from(createdAt), saved.getId());
        }
        return saved;
    }

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    private String claimBody(UUID wh, UUID area, String instance) {
        return "{\"warehouseId\":\"" + wh + "\",\"areaId\":\"" + area + "\",\"instanceId\":\"" + instance + "\"}";
    }

    @Test
    void claimNextReservesExactlyOneTaskInTheArea() throws Exception {
        UUID wh = UUID.randomUUID();
        UUID area = UUID.randomUUID();
        UUID face = UUID.randomUUID();
        ReplenishmentTask t = task(wh, face, "SCHEDULED", null);
        when(masterData.areaLocationIds(area)).thenReturn(List.of(face));

        mockMvc.perform(post("/api/slotting/replenishment/claim-next")
                        .header("X-Auth-User", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(claimBody(wh, area, "hh-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.taskId").value(t.getId().toString()))
                .andExpect(jsonPath("$.toLocationId").value(face.toString()))
                .andExpect(jsonPath("$.priority").value("SCHEDULED"));

        ReplenishmentTask reloaded = tasks.findById(t.getId()).orElseThrow();
        assertThat(reloaded.getReservedBy()).isEqualTo("alice");
        assertThat(reloaded.getReservedByInstance()).isEqualTo("hh-1");
        assertThat(reloaded.getReservedAt()).isNotNull();
        assertThat(reloaded.getStartedAt()).isNull();
    }

    @Test
    void claimNextReturnsNotFoundWhenAreaHasNoEligibleTask() throws Exception {
        UUID wh = UUID.randomUUID();
        UUID area = UUID.randomUUID();
        // A PLANNED task exists, but its pick face is NOT in the area subtree.
        task(wh, UUID.randomUUID(), "EMERGENCY", null);
        when(masterData.areaLocationIds(area)).thenReturn(List.of(UUID.randomUUID()));

        mockMvc.perform(post("/api/slotting/replenishment/claim-next")
                        .header("X-Auth-User", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(claimBody(wh, area, "hh-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(false));
    }

    @Test
    void claimNextPrefersEmergencyThenOldest() throws Exception {
        UUID wh = UUID.randomUUID();
        UUID area = UUID.randomUUID();
        UUID faceSched = UUID.randomUUID();
        UUID faceEmerg = UUID.randomUUID();
        Instant now = Instant.now();
        // Older SCHEDULED, newer EMERGENCY: EMERGENCY must win despite being newer.
        task(wh, faceSched, "SCHEDULED", now.minus(60, ChronoUnit.MINUTES));
        ReplenishmentTask emerg = task(wh, faceEmerg, "EMERGENCY", now.minus(5, ChronoUnit.MINUTES));
        when(masterData.areaLocationIds(area)).thenReturn(List.of(faceSched, faceEmerg));

        mockMvc.perform(post("/api/slotting/replenishment/claim-next")
                        .header("X-Auth-User", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(claimBody(wh, area, "hh-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.taskId").value(emerg.getId().toString()))
                .andExpect(jsonPath("$.priority").value("EMERGENCY"));
    }

    @Test
    void twoConcurrentClaimsGetDifferentTasks() throws Exception {
        UUID wh = UUID.randomUUID();
        UUID area = UUID.randomUUID();
        UUID faceA = UUID.randomUUID();
        UUID faceB = UUID.randomUUID();
        UUID idA = task(wh, faceA, "SCHEDULED", Instant.now().minus(10, ChronoUnit.MINUTES)).getId();
        UUID idB = task(wh, faceB, "SCHEDULED", Instant.now().minus(5, ChronoUnit.MINUTES)).getId();
        when(masterData.areaLocationIds(area)).thenReturn(List.of(faceA, faceB));

        // Two operators claim concurrently; SKIP LOCKED must hand them different rows.
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<UUID> claim = () -> {
            start.await();
            return reservations.claimNext(wh, area, "op-" + Thread.currentThread().getId(),
                    "inst-" + Thread.currentThread().getId()).taskId();
        };
        var f1 = pool.submit(claim);
        var f2 = pool.submit(claim);
        start.countDown();
        UUID got1 = f1.get();
        UUID got2 = f2.get();
        pool.shutdown();

        assertThat(got1).isNotNull();
        assertThat(got2).isNotNull();
        assertThat(got1).isNotEqualTo(got2);
        assertThat(List.of(got1, got2)).containsExactlyInAnyOrder(idA, idB);
    }

    @Test
    void releaseFreesOnlyNotStartedReservationsForTheInstance() {
        UUID wh = UUID.randomUUID();
        UUID area = UUID.randomUUID();
        UUID faceOpen = UUID.randomUUID();
        UUID faceStarted = UUID.randomUUID();
        when(masterData.areaLocationIds(area)).thenReturn(List.of(faceOpen, faceStarted));

        ReplenishmentTask open = task(wh, faceOpen, "SCHEDULED", null);
        ReplenishmentTask started = task(wh, faceStarted, "SCHEDULED", null);
        // Reserve both to the same instance; mark one as started (committed).
        reserve(open, "inst-1");
        started = reserve(started, "inst-1");
        started.setStartedAt(Instant.now());
        tasks.save(started);

        int freed = reservations.release("inst-1");
        assertThat(freed).isEqualTo(1);

        assertThat(tasks.findById(open.getId()).orElseThrow().getReservedBy()).isNull();
        ReplenishmentTask reloadedStarted = tasks.findById(started.getId()).orElseThrow();
        assertThat(reloadedStarted.getReservedBy()).isEqualTo("op");
        assertThat(reloadedStarted.getStartedAt()).isNotNull();

        // Idempotent: a second release frees nothing.
        assertThat(reservations.release("inst-1")).isZero();
    }

    @Test
    void sweeperFreesStaleButSkipsStarted() {
        UUID wh = UUID.randomUUID();
        UUID faceStale = UUID.randomUUID();
        UUID faceStarted = UUID.randomUUID();
        UUID faceFresh = UUID.randomUUID();

        ReplenishmentTask stale = task(wh, faceStale, "SCHEDULED", null);
        ReplenishmentTask started = task(wh, faceStarted, "SCHEDULED", null);
        ReplenishmentTask fresh = task(wh, faceFresh, "SCHEDULED", null);
        reserve(stale, "inst-stale");
        started = reserve(started, "inst-started");
        reserve(fresh, "inst-fresh");
        // Backdate the stale + started reservations beyond the 15m default TTL.
        backdateReservedAt(stale.getId(), Instant.now().minus(30, ChronoUnit.MINUTES));
        backdateReservedAt(started.getId(), Instant.now().minus(30, ChronoUnit.MINUTES));
        started.setStartedAt(Instant.now());
        tasks.save(started);

        int freed = reservations.sweepStale();
        assertThat(freed).isEqualTo(1);

        assertThat(tasks.findById(stale.getId()).orElseThrow().getReservedBy()).isNull();
        assertThat(tasks.findById(started.getId()).orElseThrow().getReservedBy()).isEqualTo("op");
        assertThat(tasks.findById(fresh.getId()).orElseThrow().getReservedBy()).isEqualTo("op");
    }

    private ReplenishmentTask reserve(ReplenishmentTask t, String instance) {
        t.setReservedBy("op");
        t.setReservedAt(Instant.now());
        t.setReservedByInstance(instance);
        return tasks.save(t);
    }

    private void backdateReservedAt(UUID id, Instant when) {
        jdbc.update("update slotting.replenishment_task set reserved_at = ? where replenishment_task_id = ?",
                java.sql.Timestamp.from(when), id);
    }
}
