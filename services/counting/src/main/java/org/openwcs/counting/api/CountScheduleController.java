package org.openwcs.counting.api;

import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.openwcs.counting.domain.CountSchedule;
import org.openwcs.counting.domain.CountTask;
import org.openwcs.common.security.AccessControl;
import org.openwcs.counting.service.CountScheduleService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * ABC-cadence schedule API: create/list schedules and manually trigger the sweep that emits due
 * count tasks (the same work the off-peak {@code CountScheduleGenerator} cron does).
 */
@RestController
@RequestMapping("/api/counting/schedules")
public class CountScheduleController {

    private final CountScheduleService schedules;

    public CountScheduleController(CountScheduleService schedules) {
        this.schedules = schedules;
    }

    @PostMapping
    public ResponseEntity<CountSchedule> create(
            @Valid @RequestBody CreateScheduleRequest request,
            @RequestHeader(name = "X-Auth-Warehouses", required = false) String warehouses) {
        requireWarehouse(warehouses, request.warehouseId());
        CountSchedule saved = schedules.create(request.toEntity());
        return ResponseEntity
                .created(URI.create("/api/counting/schedules/" + saved.getId()))
                .body(saved);
    }

    /** 403 if the caller is warehouse-scoped and the body targets a warehouse outside their set. */
    private static void requireWarehouse(String warehouses, UUID warehouseId) {
        if (!AccessControl.warehouseAllowed(warehouses, warehouseId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not permitted for this warehouse.");
        }
    }

    @GetMapping
    public List<CountSchedule> list(@RequestParam UUID warehouseId) {
        return schedules.list(warehouseId);
    }

    @GetMapping("/{scheduleId}")
    public CountSchedule get(@PathVariable UUID scheduleId) {
        return schedules.get(scheduleId);
    }

    /** Manually run the ABC-cadence sweep (optionally for one warehouse); returns the emitted tasks. */
    @PostMapping("/generate")
    public List<CountTask> generate(@RequestParam(required = false) UUID warehouseId) {
        return schedules.generateDueTasks(warehouseId, Instant.now());
    }
}
