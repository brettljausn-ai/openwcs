package org.openwcs.allocation.api;

import jakarta.validation.Valid;
import java.util.UUID;
import org.openwcs.allocation.service.BatchingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Build and inspect pick batches (ADR 0002 §6). */
@RestController
@RequestMapping("/api/allocation/batches")
public class BatchController {

    private final BatchingService service;

    public BatchController(BatchingService service) {
        this.service = service;
    }

    @PostMapping
    public BatchingResult build(@Valid @RequestBody BuildBatchesRequest request) {
        return service.buildBatches(request.warehouseId(), request.orderRefs());
    }

    @GetMapping("/{batchId}")
    public PickBatchView get(@PathVariable UUID batchId) {
        return service.get(batchId);
    }
}
