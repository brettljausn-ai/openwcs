package org.openwcs.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.inventory.client.MasterDataClient;
import org.openwcs.inventory.domain.Stock;
import org.openwcs.inventory.repo.StockRepository;
import org.openwcs.inventory.service.Availability;
import org.openwcs.inventory.service.InsufficientStockException;
import org.openwcs.inventory.service.InventoryService;
import org.openwcs.inventory.service.ReserveCommand;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Allocation guard for the UNKNOWN location against a real PostgreSQL 16: stock booked there
 * (HUs whose position nobody knows) contributes ZERO to availability / ATP and can never be
 * reserved, while overview-style reads still show it (admins must see stock at UNKNOWN).
 */
@SpringBootTest
@Testcontainers
class UnknownLocationAllocationTest {

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
    InventoryService service;

    @Autowired
    StockRepository stock;

    @MockBean
    MasterDataClient masterData;

    private void seedAvailable(UUID warehouseId, UUID skuId, UUID locationId, UUID huId, String qty) {
        Stock row = new Stock();
        row.setWarehouseId(warehouseId);
        row.setSkuId(skuId);
        row.setLocationId(locationId);
        row.setHuId(huId);
        row.setStatus("AVAILABLE");
        row.setQty(new BigDecimal(qty));
        stock.save(row);
    }

    @Test
    void stockAtUnknownGivesZeroAtpAndCannotBeReserved() {
        UUID wh = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        UUID unknownLocation = UUID.randomUUID();
        when(masterData.unknownLocationId(wh)).thenReturn(unknownLocation);
        // An HU with qty 10 booked to UNKNOWN (position not known).
        seedAvailable(wh, sku, unknownLocation, UUID.randomUUID(), "10");

        Availability a = service.availability(wh, sku);
        assertThat(a.onHand()).isEqualByComparingTo("0");
        assertThat(a.availableToPromise()).isEqualByComparingTo("0");

        Availability atUnknown = service.availabilityAtLocation(wh, sku, unknownLocation);
        assertThat(atUnknown.onHand()).isEqualByComparingTo("0");
        assertThat(atUnknown.availableToPromise()).isEqualByComparingTo("0");

        // Warehouse-wide reservation: the UNKNOWN rows are excluded from the ATP check.
        assertThatThrownBy(() -> service.reserve(
                new ReserveCommand(wh, sku, new BigDecimal("1"), null, null, null, "ORD-UNK-1", null, null)))
                .isInstanceOf(InsufficientStockException.class);

        // Location-scoped reservation aimed straight at UNKNOWN is rejected too.
        assertThatThrownBy(() -> service.reserve(
                new ReserveCommand(wh, sku, new BigDecimal("1"), null, unknownLocation, null, "ORD-UNK-2", null, null)))
                .isInstanceOf(InsufficientStockException.class);
    }

    @Test
    void unknownStockStaysVisibleInOverviewStyleReads() {
        UUID wh = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        UUID unknownLocation = UUID.randomUUID();
        when(masterData.unknownLocationId(wh)).thenReturn(unknownLocation);
        seedAvailable(wh, sku, unknownLocation, UUID.randomUUID(), "10");

        // Admins must see stock at UNKNOWN: the overview row and the bucket list keep showing it.
        assertThat(service.stockOverview(wh))
                .anySatisfy(row -> {
                    assertThat(row.locationId()).isEqualTo(unknownLocation);
                    assertThat(row.qty()).isEqualByComparingTo("10");
                });
        assertThat(service.listStock(wh, sku))
                .anySatisfy(bucket -> assertThat(bucket.getLocationId()).isEqualTo(unknownLocation));
    }

    @Test
    void stockAtRealLocationsIsUnaffectedByTheUnknownExclusion() {
        UUID wh = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        UUID unknownLocation = UUID.randomUUID();
        when(masterData.unknownLocationId(wh)).thenReturn(unknownLocation);
        seedAvailable(wh, sku, unknownLocation, UUID.randomUUID(), "10");
        seedAvailable(wh, sku, UUID.randomUUID(), null, "5");

        // Only the real-location qty counts: ATP is 5, not 15.
        assertThat(service.availability(wh, sku).availableToPromise()).isEqualByComparingTo("5");

        service.reserve(new ReserveCommand(wh, sku, new BigDecimal("5"), null, null, null, "ORD-UNK-3", null, null));
        assertThatThrownBy(() -> service.reserve(
                new ReserveCommand(wh, sku, new BigDecimal("1"), null, null, null, "ORD-UNK-4", null, null)))
                .isInstanceOf(InsufficientStockException.class);
    }
}
