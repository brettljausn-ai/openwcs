package org.openwcs.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.inventory.api.StockBySkuRow;
import org.openwcs.inventory.client.MasterDataClient;
import org.openwcs.inventory.client.MasterDataUnavailableException;
import org.openwcs.inventory.domain.Reservation;
import org.openwcs.inventory.domain.Stock;
import org.openwcs.inventory.repo.ReservationRepository;
import org.openwcs.inventory.repo.StockRepository;
import org.openwcs.inventory.service.InventoryReportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Stock-by-SKU report against a real PostgreSQL 16: AVAILABLE stock minus active holds is
 * "available", HELD reservations are "allocated", and non-AVAILABLE stock plus anything at
 * the warehouse's UNKNOWN location is "unavailable".
 */
@SpringBootTest
@Testcontainers
class StockBySkuReportTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("openwcs.inventory.density-sweep.enabled", () -> "false");
    }

    @Autowired
    InventoryReportService reports;

    @Autowired
    StockRepository stock;

    @Autowired
    ReservationRepository reservations;

    @MockBean
    MasterDataClient masterData;

    private void seedStock(UUID wh, UUID sku, UUID location, String status, String qty) {
        Stock row = new Stock();
        row.setWarehouseId(wh);
        row.setSkuId(sku);
        row.setLocationId(location);
        row.setStatus(status);
        row.setQty(new BigDecimal(qty));
        stock.save(row);
    }

    private void seedHold(UUID wh, UUID sku, String qty, String status) {
        Reservation r = new Reservation();
        r.setWarehouseId(wh);
        r.setSkuId(sku);
        r.setQty(new BigDecimal(qty));
        r.setStatus(status);
        r.setOrderRef("ORD-RPT");
        reservations.save(r);
    }

    @Test
    void splitsAvailableAllocatedAndUnavailablePerSku() {
        UUID wh = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        when(masterData.unknownLocationId(wh)).thenReturn(unknown);

        UUID sku = UUID.randomUUID();
        seedStock(wh, sku, UUID.randomUUID(), "AVAILABLE", "100");  // usable
        seedStock(wh, sku, UUID.randomUUID(), "QUARANTINE", "5");   // unavailable by status
        seedStock(wh, sku, unknown, "AVAILABLE", "10");             // unavailable: at UNKNOWN
        seedHold(wh, sku, "30", "HELD");                            // allocated
        seedHold(wh, sku, "7", "RELEASED");                         // inactive, must not count

        List<StockBySkuRow> rows = reports.stockBySku(wh);
        assertThat(rows).hasSize(1);
        StockBySkuRow row = rows.get(0);
        assertThat(row.skuId()).isEqualTo(sku);
        assertThat(row.available()).isEqualByComparingTo("70");   // 100 AVAILABLE − 30 held
        assertThat(row.allocated()).isEqualByComparingTo("30");
        assertThat(row.unavailable()).isEqualByComparingTo("15"); // 5 quarantine + 10 at UNKNOWN
    }

    @Test
    void overAllocatedSkuFloorsAvailableAtZero() {
        UUID wh = UUID.randomUUID();
        when(masterData.unknownLocationId(wh)).thenReturn(UUID.randomUUID());

        UUID sku = UUID.randomUUID();
        seedStock(wh, sku, UUID.randomUUID(), "AVAILABLE", "10");
        seedHold(wh, sku, "25", "HELD");

        List<StockBySkuRow> rows = reports.stockBySku(wh);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).available()).isEqualByComparingTo("0");
        assertThat(rows.get(0).allocated()).isEqualByComparingTo("25");
    }

    @Test
    void answersWithoutUnknownSplitWhenMasterDataIsDown() {
        UUID wh = UUID.randomUUID();
        when(masterData.unknownLocationId(wh))
                .thenThrow(new MasterDataUnavailableException("down", null));

        UUID sku = UUID.randomUUID();
        seedStock(wh, sku, UUID.randomUUID(), "AVAILABLE", "8");

        List<StockBySkuRow> rows = reports.stockBySku(wh);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).available()).isEqualByComparingTo("8");
        assertThat(rows.get(0).unavailable()).isEqualByComparingTo("0");
    }
}
