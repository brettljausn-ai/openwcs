package org.openwcs.slotting;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.slotting.client.InventoryClient;
import org.openwcs.slotting.client.MasterDataClient;
import org.openwcs.slotting.domain.PickSlot;
import org.openwcs.slotting.domain.ReplenishmentTask;
import org.openwcs.slotting.domain.SkuVelocity;
import org.openwcs.slotting.repo.PickSlotRepository;
import org.openwcs.slotting.repo.ReplenishmentTaskRepository;
import org.openwcs.slotting.repo.SkuVelocityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Read-only dashboard endpoints (Dashboards &amp; alerting epic):
 * {@code GET /api/slotting/replenishment/dashboard} and {@code GET /api/slotting/velocity/abc}.
 * Verifies JSON shapes, ABC counts, Pareto cumulative %, top/bottom ordering, riser/faller
 * selection, oldest-age, and warehouse-scope RBAC.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class DashboardApiTest {

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
    PickSlotRepository pickSlots;

    @Autowired
    SkuVelocityRepository velocity;

    @Autowired
    JdbcTemplate jdbc;

    @MockBean
    MasterDataClient masterData;

    @MockBean
    InventoryClient inventory;

    private ReplenishmentTask task(UUID wh, String priority, Instant createdAt) {
        ReplenishmentTask t = new ReplenishmentTask();
        t.setWarehouseId(wh);
        t.setSkuId(UUID.randomUUID());
        t.setUomId(UUID.randomUUID());
        t.setToLocationId(UUID.randomUUID());
        t.setQty(new BigDecimal("10"));
        t.setPriority(priority);
        t.setTriggerType("BELOW_MIN");
        t.setStatus("PLANNED");
        ReplenishmentTask saved = tasks.save(t);
        // @CreationTimestamp stamps now; override to exercise the oldest-age calculation.
        if (createdAt != null) {
            jdbc.update("update slotting.replenishment_task set created_at = ? where replenishment_task_id = ?",
                    java.sql.Timestamp.from(createdAt), saved.getId());
        }
        return saved;
    }

    private SkuVelocity vel(UUID wh, UUID skuId, String cls, String score, String pending) {
        SkuVelocity v = new SkuVelocity();
        v.setWarehouseId(wh);
        v.setSkuId(skuId);
        v.setVelocityClass(cls);
        v.setScore(new BigDecimal(score));
        v.setPendingPicks(new BigDecimal(pending));
        v.setLastPickAt(Instant.now());
        return velocity.save(v);
    }

    // ---- replenishment dashboard ----

    @Test
    void replenishmentDashboardCountsUrgentOpenAndOldestAge() throws Exception {
        UUID wh = UUID.randomUUID();
        Instant now = Instant.now();
        task(wh, "EMERGENCY", now.minus(120, ChronoUnit.MINUTES)); // oldest
        task(wh, "SCHEDULED", now.minus(30, ChronoUnit.MINUTES));
        task(wh, "SCHEDULED", now.minus(5, ChronoUnit.MINUTES));

        when(inventory.onHandAtLocation(any(), any(), any())).thenReturn(BigDecimal.ZERO);

        mockMvc.perform(get("/api/slotting/replenishment/dashboard").param("warehouseId", wh.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.urgent").value(1))
                .andExpect(jsonPath("$.openTasks").value(3))
                .andExpect(jsonPath("$.oldestAgeMin").value(org.hamcrest.Matchers.greaterThanOrEqualTo(119)))
                .andExpect(jsonPath("$.projectedStockouts").isArray());
    }

    @Test
    void replenishmentDashboardProjectsStockoutFromVelocityVsOnHand() throws Exception {
        UUID wh = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        UUID loc = UUID.randomUUID();
        PickSlot slot = new PickSlot();
        slot.setWarehouseId(wh);
        slot.setLocationId(loc);
        slot.setSkuId(sku);
        slot.setUomId(UUID.randomUUID());
        slot.setMinQty(new BigDecimal("5"));
        slot.setMaxQty(new BigDecimal("50"));
        pickSlots.save(slot);

        // High velocity score so picks/min is non-trivial; on-hand 100.
        vel(wh, sku, "A", "1440", "0"); // 1440 picks over 14d window
        when(inventory.onHandAtLocation(eq(wh), eq(sku), eq(loc))).thenReturn(new BigDecimal("100"));

        mockMvc.perform(get("/api/slotting/replenishment/dashboard").param("warehouseId", wh.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectedStockouts.length()").value(1))
                .andExpect(jsonPath("$.projectedStockouts[0].locationCode").value(loc.toString()))
                .andExpect(jsonPath("$.projectedStockouts[0].minutesToStockout")
                        .value(org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test
    void replenishmentDashboardRejectsForeignWarehouse() throws Exception {
        UUID wh = UUID.randomUUID();
        mockMvc.perform(get("/api/slotting/replenishment/dashboard")
                        .header("X-Auth-Warehouses", UUID.randomUUID().toString())
                        .param("warehouseId", wh.toString()))
                .andExpect(status().isForbidden());
    }

    // ---- velocity ABC ----

    @Test
    void abcReturnsClassCountsParetoTopBottomAndTrends() throws Exception {
        UUID wh = UUID.randomUUID();
        UUID a1 = UUID.randomUUID();
        UUID a2 = UUID.randomUUID();
        UUID b1 = UUID.randomUUID();
        UUID c1 = UUID.randomUUID();

        // scores 100, 60, 30, 10 -> total 200; cumulative 50, 80, 95, 100
        vel(wh, a1, "A", "100", "0");
        vel(wh, a2, "A", "60", "0");
        vel(wh, b1, "B", "30", "0");
        vel(wh, c1, "C", "10", "0");

        mockMvc.perform(get("/api/slotting/velocity/abc").param("warehouseId", wh.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.a").value(2))
                .andExpect(jsonPath("$.b").value(1))
                .andExpect(jsonPath("$.c").value(1))
                .andExpect(jsonPath("$.pareto.length()").value(4))
                .andExpect(jsonPath("$.pareto[0].skuId").value(a1.toString()))
                .andExpect(jsonPath("$.pareto[0].cumPct").value(50.00))
                .andExpect(jsonPath("$.pareto[1].cumPct").value(80.00))
                .andExpect(jsonPath("$.pareto[3].cumPct").value(100.00))
                .andExpect(jsonPath("$.top[0].skuId").value(a1.toString()))
                .andExpect(jsonPath("$.bottom[0].skuId").value(c1.toString()));
    }

    @Test
    void abcRisersAndFallersByShortVsLongWindow() throws Exception {
        UUID wh = UUID.randomUUID();
        UUID riser = UUID.randomUUID();   // big recent pending, small established score
        UUID faller = UUID.randomUUID();  // no recent pending, big established score
        UUID steady = UUID.randomUUID();

        vel(wh, riser, "B", "10", "90");   // short share high, long share low -> riser
        vel(wh, faller, "A", "90", "0");   // short share 0, long share high -> faller
        vel(wh, steady, "B", "20", "10");

        mockMvc.perform(get("/api/slotting/velocity/abc").param("warehouseId", wh.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.risers[0].skuId").value(riser.toString()))
                .andExpect(jsonPath("$.fallers[0].skuId").value(faller.toString()));
    }

    @Test
    void abcEmptyWarehouseReturnsZeros() throws Exception {
        UUID wh = UUID.randomUUID();
        mockMvc.perform(get("/api/slotting/velocity/abc").param("warehouseId", wh.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.a").value(0))
                .andExpect(jsonPath("$.b").value(0))
                .andExpect(jsonPath("$.c").value(0))
                .andExpect(jsonPath("$.pareto.length()").value(0))
                .andExpect(jsonPath("$.top.length()").value(0))
                .andExpect(jsonPath("$.bottom.length()").value(0));
    }

    @Test
    void abcRejectsForeignWarehouse() throws Exception {
        UUID wh = UUID.randomUUID();
        mockMvc.perform(get("/api/slotting/velocity/abc")
                        .header("X-Auth-Warehouses", UUID.randomUUID().toString())
                        .param("warehouseId", wh.toString()))
                .andExpect(status().isForbidden());
    }
}
