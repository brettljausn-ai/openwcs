package org.openwcs.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.openwcs.inventory.api.DemoSeedRequest;
import org.openwcs.inventory.api.LocationUpdateRequest;
import org.openwcs.inventory.client.MasterDataClient;
import org.openwcs.inventory.domain.HandlingUnit;
import org.openwcs.inventory.domain.Stock;
import org.openwcs.inventory.repo.HandlingUnitRepository;
import org.openwcs.inventory.repo.StockRepository;
import org.openwcs.inventory.service.DemoSeedService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Reproduces the demo-off "leak" hypothesis against real PostgreSQL: a transport-lifecycle location
 * booking ({@code PUT /handling-units/{id}/location}) that RACES the warehouse-scoped demo clear must
 * NEVER resurrect a deleted handling unit, and the clear itself must remove every HU in the warehouse
 * regardless of which kind of location (ASRS slot vs conveyor/station operational location) it sits in.
 *
 * <p>The bug report claimed only HUs at operational locations (a CONVEYOR_SEGMENT and a STATION)
 * survived demo-off while ~72 ASRS-slot HUs were deleted. These tests pin the actual behaviour: the
 * clear is location-blind (warehouse-scoped) and a concurrent booking cannot re-insert an HU.
 */
@SpringBootTest
@Testcontainers
class DemoClearRaceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @Autowired
    DemoSeedService demo;

    @Autowired
    org.openwcs.inventory.api.HandlingUnitController controller;

    @Autowired
    HandlingUnitRepository handlingUnits;

    @Autowired
    StockRepository stock;

    @Autowired
    TransactionTemplate tx;

    @MockBean
    MasterDataClient masterData;

    /** The clear is location-blind: HUs at conveyor/station/UNKNOWN locations go too, not just slots. */
    @Test
    void clearRemovesOperationalLocationHusNotJustAsrsSlots() {
        UUID warehouse = UUID.randomUUID();
        UUID conveyor = UUID.randomUUID(); // CONVEYOR_SEGMENT (e.g. ASRS-1) in master-data
        UUID station = UUID.randomUUID();  // STATION (e.g. PP1)
        UUID slotA = UUID.randomUUID();
        UUID slotB = UUID.randomUUID();

        HandlingUnit atConveyor = save(hu("DEMO-HU-000", warehouse, conveyor));
        HandlingUnit atStation = save(hu("DEMO-HU-001", warehouse, station));
        save(hu("DEMO-HU-002", warehouse, slotA));
        save(hu("DEMO-HU-003", warehouse, slotB));

        assertThat(handlingUnits.findByWarehouseId(warehouse)).hasSize(4);

        demo.clear(warehouse);

        // Every HU in the warehouse is gone, including the two on operational locations.
        assertThat(handlingUnits.findByWarehouseId(warehouse)).isEmpty();
        assertThat(handlingUnits.findById(atConveyor.getHuId())).isEmpty();
        assertThat(handlingUnits.findById(atStation.getHuId())).isEmpty();
    }

    /** Seeding then clearing the demo set leaves zero HUs, whatever locations they were placed at. */
    @Test
    void seedThenClearLeavesNoHandlingUnits() {
        UUID warehouse = UUID.randomUUID();
        List<UUID> locations = List.of(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        List<UUID> skus = List.of(UUID.randomUUID(), UUID.randomUUID());

        demo.seed(new DemoSeedRequest(warehouse, UUID.randomUUID(), locations, skus));
        assertThat(handlingUnits.findByWarehouseId(warehouse)).isNotEmpty();

        demo.clear(warehouse);
        assertThat(handlingUnits.findByWarehouseId(warehouse)).isEmpty();
    }

    /**
     * A late transport callback books the (now-deleted) HU's location. updateLocation first does
     * findById, which is empty -> 404, so it cannot resurrect. Flow swallows this.
     */
    @Test
    void bookingThatRacesTheClearDoesNotResurrectTheHu() {
        UUID warehouse = UUID.randomUUID();
        UUID conveyor = UUID.randomUUID();
        UUID slot = UUID.randomUUID();
        HandlingUnit tote = save(hu("DEMO-HU-000", warehouse, conveyor));
        stockInHu(warehouse, tote.getHuId(), conveyor, "5");
        UUID id = tote.getHuId();

        demo.clear(warehouse);
        assertThat(handlingUnits.findById(id)).isEmpty();

        boolean threw = false;
        try {
            controller.updateLocation(id, new LocationUpdateRequest(slot));
        } catch (RuntimeException e) {
            threw = true;
        }
        assertThat(threw).isTrue();
        assertThat(handlingUnits.findById(id)).isEmpty();
        assertThat(handlingUnits.findByWarehouseId(warehouse)).isEmpty();
    }

    /**
     * The harder race: the booking transaction LOADS the managed HU first, THEN a parallel clear
     * deletes-and-commits, THEN the booking flushes its UPDATE. A @Version-ed managed entity whose row
     * vanished must throw on flush, never re-insert. Proven with a real second connection committing the
     * delete mid-transaction.
     */
    @Test
    void managedEntityUpdateAfterConcurrentDeleteThrowsAndDoesNotReinsert() throws Exception {
        UUID warehouse = UUID.randomUUID();
        UUID conveyor = UUID.randomUUID();
        UUID slot = UUID.randomUUID();
        HandlingUnit tote = save(hu("DEMO-HU-000", warehouse, conveyor));
        UUID id = tote.getHuId();

        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch deleted = new CountDownLatch(1);

        Thread bookingThread = new Thread(() -> {
            try {
                tx.executeWithoutResult(s -> {
                    HandlingUnit existing = handlingUnits.findById(id).orElseThrow();
                    loaded.countDown();
                    try {
                        deleted.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    existing.setLocationId(slot);
                    handlingUnits.save(existing);
                    handlingUnits.flush(); // force the UPDATE against the deleted row
                });
            } catch (RuntimeException expected) {
                // optimistic-lock / stale state: the booking transaction rolls back.
            }
        });
        bookingThread.start();

        loaded.await(10, TimeUnit.SECONDS);
        // Parallel clear commits while the booking holds its loaded entity.
        tx.executeWithoutResult(s -> handlingUnits.deleteBulkByWarehouseId(warehouse));
        deleted.countDown();
        bookingThread.join(15_000);

        // The deleted HU stays deleted: the racing booking neither survived nor re-inserted it.
        assertThat(handlingUnits.findById(id)).isEmpty();
        assertThat(handlingUnits.findByWarehouseId(warehouse)).isEmpty();
    }

    private HandlingUnit hu(String code, UUID warehouseId, UUID locationId) {
        HandlingUnit hu = new HandlingUnit();
        hu.setWarehouseId(warehouseId);
        hu.setCode(code);
        hu.setLocationId(locationId);
        hu.setStatus("ACTIVE");
        return hu;
    }

    private HandlingUnit save(HandlingUnit hu) {
        return handlingUnits.saveAndFlush(hu);
    }

    private void stockInHu(UUID warehouseId, UUID huId, UUID locationId, String qty) {
        Stock row = new Stock();
        row.setWarehouseId(warehouseId);
        row.setSkuId(UUID.randomUUID());
        row.setLocationId(locationId);
        row.setHuId(huId);
        row.setStatus("AVAILABLE");
        row.setQty(new BigDecimal(qty));
        stock.saveAndFlush(row);
    }
}
