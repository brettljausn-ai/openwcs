package org.openwcs.allocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.allocation.api.StockBlockingReport;
import org.openwcs.allocation.client.InventoryClient;
import org.openwcs.allocation.domain.AllocationLine;
import org.openwcs.allocation.domain.OrderAllocation;
import org.openwcs.allocation.repo.OrderAllocationRepository;
import org.openwcs.allocation.service.AllocationReportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Stock-blocking report against a real PostgreSQL 16 with the inventory client mocked. Blocked
 * lines are SHORT allocation lines on non-cancelled orders; the recoverable/zero split asks
 * inventory whether the SKU still has stock somewhere, and degrades to all-zero-stock when
 * inventory is unreachable.
 */
@SpringBootTest
@Testcontainers
class StockBlockingReportTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockBean
    org.openwcs.allocation.client.MasterDataClient masterData;

    @MockBean
    InventoryClient inventory;

    @MockBean
    org.openwcs.allocation.client.HostLabelClient hostLabel;

    @Autowired
    AllocationReportService reports;

    @Autowired
    OrderAllocationRepository allocations;

    private AllocationLine line(int lineNo, UUID sku, String status, String qty) {
        AllocationLine l = new AllocationLine();
        l.setLineNo(lineNo);
        l.setSkuId(sku);
        l.setRequestedQty(new BigDecimal(qty));
        l.setStatus(status);
        return l;
    }

    private OrderAllocation order(UUID wh, String ref, String status, AllocationLine... lines) {
        OrderAllocation o = new OrderAllocation();
        o.setOrderRef(ref);
        o.setWarehouseId(wh);
        o.setStatus(status);
        o.setCubingMode("APP");
        for (AllocationLine l : lines) {
            o.addLine(l);
        }
        return allocations.save(o);
    }

    @Test
    void splitsBlockedLinesIntoRecoverableAndZeroStock() {
        UUID wh = UUID.randomUUID();
        UUID skuStock = UUID.randomUUID();   // short but stock exists somewhere -> recoverable
        UUID skuEmpty = UUID.randomUUID();   // short and truly zero -> zeroStock
        UUID skuOk = UUID.randomUUID();      // fully allocated, not blocked

        order(wh, "ORD-A", "FULFILLABLE_SHORT",
                line(1, skuStock, "SHORT", "5"),
                line(2, skuOk, "ALLOCATED", "3"));
        order(wh, "ORD-B", "NOT_FULFILLABLE",
                line(1, skuEmpty, "SHORT", "2"),
                line(2, skuStock, "SHORT", "1")); // second blocked line on the recoverable SKU
        // A cancelled order must not contribute any line.
        order(wh, "ORD-C", "CANCELLED", line(1, skuEmpty, "SHORT", "9"));

        when(inventory.available(eq(wh), eq(skuStock))).thenReturn(new BigDecimal("12"));
        when(inventory.available(eq(wh), eq(skuEmpty))).thenReturn(BigDecimal.ZERO);

        StockBlockingReport r = reports.stockBlocking(wh);

        assertThat(r.blockedLines()).isEqualTo(3);          // 2 on skuStock + 1 on skuEmpty
        assertThat(r.distinctSkusShort()).isEqualTo(2);     // skuStock, skuEmpty
        assertThat(r.recoverableLines()).isEqualTo(2);      // both skuStock SHORT lines
        assertThat(r.zeroStockLines()).isEqualTo(1);        // the skuEmpty SHORT line
        assertThat(r.openOutboundLines()).isEqualTo(4);     // ORD-A 2 + ORD-B 2, ORD-C excluded
    }

    @Test
    void degradesToZeroStockWhenInventoryUnreachable() {
        UUID wh = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        order(wh, "ORD-D", "NOT_FULFILLABLE",
                line(1, sku, "SHORT", "5"),
                line(2, sku, "SHORT", "2"));

        when(inventory.available(any(), any()))
                .thenThrow(new RuntimeException("inventory down"));

        StockBlockingReport r = reports.stockBlocking(wh);

        assertThat(r.blockedLines()).isEqualTo(2);
        assertThat(r.distinctSkusShort()).isEqualTo(1);
        assertThat(r.recoverableLines()).isZero();           // cannot prove stock -> none recoverable
        assertThat(r.zeroStockLines()).isEqualTo(2);         // all blocked lines fall to zero-stock
        assertThat(r.openOutboundLines()).isEqualTo(2);
    }

    @Test
    void noBlockedLinesWhenEverythingAllocated() {
        UUID wh = UUID.randomUUID();
        order(wh, "ORD-E", "FULFILLABLE",
                line(1, UUID.randomUUID(), "ALLOCATED", "5"));

        StockBlockingReport r = reports.stockBlocking(wh);

        assertThat(r.blockedLines()).isZero();
        assertThat(r.distinctSkusShort()).isZero();
        assertThat(r.recoverableLines()).isZero();
        assertThat(r.zeroStockLines()).isZero();
        assertThat(r.openOutboundLines()).isEqualTo(1);
    }
}
