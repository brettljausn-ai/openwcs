package org.openwcs.allocation.api;

import java.util.UUID;
import org.openwcs.allocation.service.AllocationReportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reporting aggregates for the dashboards / alerting. Read-only; like every allocation GET
 * these require ORDER_VIEW (enforced by {@link RbacFilter} when security is enabled).
 */
@RestController
@RequestMapping("/api/allocation/reports")
public class AllocationReportController {

    private final AllocationReportService reports;

    public AllocationReportController(AllocationReportService reports) {
        this.reports = reports;
    }

    /**
     * Stock-blocking KPIs for one warehouse: blocked (short) outbound lines, distinct short
     * SKUs, the recoverable/zero-stock split, and the open-outbound-line denominator. Best-effort
     * (the recoverable/zero split degrades to zero-stock when inventory is unreachable).
     */
    @GetMapping("/stock-blocking")
    public StockBlockingReport stockBlocking(@RequestParam UUID warehouseId) {
        return reports.stockBlocking(warehouseId);
    }
}
