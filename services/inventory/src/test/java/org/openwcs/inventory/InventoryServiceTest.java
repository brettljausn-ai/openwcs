package org.openwcs.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.inventory.domain.Stock;
import org.openwcs.inventory.repo.StockRepository;
import org.openwcs.inventory.service.Availability;
import org.openwcs.inventory.service.InsufficientStockException;
import org.openwcs.inventory.service.InventoryService;
import org.openwcs.inventory.service.ReserveCommand;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Exercises availability and the reservation lifecycle against a real PostgreSQL 16:
 * ATP = on-hand − reserved, over-allocation is rejected, and releasing frees ATP again.
 */
@SpringBootTest
@Testcontainers
class InventoryServiceTest {

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

    private void seedAvailable(UUID warehouseId, UUID skuId, UUID locationId, String qty) {
        Stock row = new Stock();
        row.setWarehouseId(warehouseId);
        row.setSkuId(skuId);
        row.setLocationId(locationId);
        row.setStatus("AVAILABLE");
        row.setQty(new BigDecimal(qty));
        stock.save(row);
    }

    @Test
    void availabilityReflectsOnHandMinusReserved() {
        UUID wh = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        seedAvailable(wh, sku, UUID.randomUUID(), "100");

        service.reserve(new ReserveCommand(wh, sku, new BigDecimal("30"), null, null, null, "ORD-1", null, null));

        Availability a = service.availability(wh, sku);
        assertThat(a.onHand()).isEqualByComparingTo("100");
        assertThat(a.reserved()).isEqualByComparingTo("30");
        assertThat(a.availableToPromise()).isEqualByComparingTo("70");
    }

    @Test
    void reservationBeyondAvailableToPromiseIsRejected() {
        UUID wh = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        seedAvailable(wh, sku, UUID.randomUUID(), "10");

        service.reserve(new ReserveCommand(wh, sku, new BigDecimal("8"), null, null, null, "ORD-2", null, null));

        assertThatThrownBy(() ->
                service.reserve(new ReserveCommand(wh, sku, new BigDecimal("5"), null, null, null, "ORD-3", null, null)))
                .isInstanceOf(InsufficientStockException.class);
    }

    @Test
    void releasingAReservationFreesAvailableToPromise() {
        UUID wh = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        seedAvailable(wh, sku, UUID.randomUUID(), "20");

        var reservation = service.reserve(
                new ReserveCommand(wh, sku, new BigDecimal("20"), null, null, null, "ORD-4", null, null));
        assertThat(service.availability(wh, sku).availableToPromise()).isEqualByComparingTo("0");

        service.release(reservation.getId());
        assertThat(service.availability(wh, sku).availableToPromise()).isEqualByComparingTo("20");
    }
}
