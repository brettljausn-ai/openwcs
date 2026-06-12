package org.openwcs.flow.api;

import java.util.List;
import java.util.UUID;
import org.openwcs.flow.api.ReportingDtos.DeviceMovementRow;
import org.openwcs.flow.api.ReportingDtos.ScanQualityRow;
import org.openwcs.flow.api.ReportingDtos.StorageMovementRow;
import org.openwcs.flow.api.ReportingDtos.TrafficRow;
import org.openwcs.flow.api.ReportingDtos.TransitTimeRow;
import org.openwcs.flow.service.ReportingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reporting API: per-day aggregates for the Reporting screens (scan quality, traffic heatmap,
 * storage/device movements, transit times). All GET; RBAC is the flow {@code RbacFilter}
 * (DEVICE_VIEW on reads, like every other flow read endpoint). {@code days} is the history window
 * (today inclusive, default 90, capped at 180).
 */
@RestController
@RequestMapping("/api/flow/reports")
public class ReportingController {

    private final ReportingService reporting;

    public ReportingController(ReportingService reporting) {
        this.reporting = reporting;
    }

    @GetMapping("/scan-quality")
    public List<ScanQualityRow> scanQuality(@RequestParam UUID warehouseId,
                                            @RequestParam(defaultValue = "90") int days) {
        return reporting.scanQuality(warehouseId, days);
    }

    @GetMapping("/traffic")
    public List<TrafficRow> traffic(@RequestParam UUID warehouseId,
                                    @RequestParam(defaultValue = "90") int days) {
        return reporting.traffic(warehouseId, days);
    }

    @GetMapping("/storage-movements")
    public List<StorageMovementRow> storageMovements(@RequestParam UUID warehouseId,
                                                     @RequestParam(defaultValue = "90") int days) {
        return reporting.storageMovements(warehouseId, days);
    }

    @GetMapping("/device-movements")
    public List<DeviceMovementRow> deviceMovements(@RequestParam UUID warehouseId,
                                                   @RequestParam(defaultValue = "90") int days) {
        return reporting.deviceMovements(warehouseId, days);
    }

    @GetMapping("/transit-times")
    public List<TransitTimeRow> transitTimes(@RequestParam UUID warehouseId,
                                             @RequestParam(defaultValue = "90") int days) {
        return reporting.transitTimes(warehouseId, days);
    }
}
