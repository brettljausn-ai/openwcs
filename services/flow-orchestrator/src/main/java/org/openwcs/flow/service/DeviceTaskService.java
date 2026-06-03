package org.openwcs.flow.service;

import java.util.List;
import java.util.UUID;
import org.openwcs.flow.api.DeviceTaskNotFoundException;
import org.openwcs.flow.api.DeviceTaskView;
import org.openwcs.flow.api.RequestDeviceTask;
import org.openwcs.flow.client.DeviceClient;
import org.openwcs.flow.domain.DeviceTask;
import org.openwcs.flow.repo.DeviceTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

/**
 * Turns a requested step into a device task and dispatches it to the equipment adapter
 * (build.md §4.5/§8). Records REQUESTED → DISPATCHED → COMPLETED/FAILED. Dispatch is
 * synchronous against the (simulator) adapter; a failed adapter call records FAILED rather
 * than losing the task.
 */
@Service
public class DeviceTaskService {

    private static final Logger log = LoggerFactory.getLogger(DeviceTaskService.class);

    private final DeviceTaskRepository tasks;
    private final DeviceClient deviceClient;

    public DeviceTaskService(DeviceTaskRepository tasks, DeviceClient deviceClient) {
        this.tasks = tasks;
        this.deviceClient = deviceClient;
    }

    @Transactional
    public DeviceTaskView request(RequestDeviceTask request, String actor) {
        DeviceTask task = new DeviceTask();
        task.setWarehouseId(request.warehouseId());
        task.setFamily(request.family());
        task.setEquipmentId(request.equipmentId());
        task.setCommand(request.command());
        if (request.payload() != null) {
            task.setPayload(request.payload());
        }
        task.setCorrelationId(request.correlationId());
        task.setActor(actor);
        task.setStatus("REQUESTED");
        tasks.saveAndFlush(task); // assign the id before dispatch

        task.setStatus("DISPATCHED");
        try {
            DeviceClient.DeviceResult result = deviceClient.execute(task);
            if (result != null && "COMPLETED".equals(result.status())) {
                task.setStatus("COMPLETED");
                task.setDetail(result.detail());
                task.setResult(result.resultPayload());
            } else {
                task.setStatus("FAILED");
                task.setDetail(result == null ? "adapter returned no result" : result.detail());
                task.setResult(result == null ? null : result.resultPayload());
            }
        } catch (RestClientException e) {
            log.warn("Device task {} dispatch failed: {}", task.getId(), e.toString());
            task.setStatus("FAILED");
            task.setDetail("adapter call failed: " + e.getMessage());
        }
        return DeviceTaskView.from(task);
    }

    @Transactional(readOnly = true)
    public DeviceTaskView get(UUID taskId) {
        return DeviceTaskView.from(tasks.findById(taskId)
                .orElseThrow(() -> new DeviceTaskNotFoundException(taskId)));
    }

    @Transactional(readOnly = true)
    public List<DeviceTaskView> byCorrelation(UUID correlationId) {
        return tasks.findByCorrelationIdOrderByCreatedAtAsc(correlationId).stream()
                .map(DeviceTaskView::from)
                .toList();
    }

    /**
     * Recent device tasks (newest first) for the transport overview. Filters are optional and
     * combine; {@code limit} is clamped to [1, 500] so the poll-driven UI can't ask for the world.
     */
    @Transactional(readOnly = true)
    public List<DeviceTaskView> search(UUID warehouseId, String status, String family, UUID equipmentId, int limit) {
        int capped = Math.max(1, Math.min(limit, 500));
        String statusFilter = blankToNull(status);
        String familyFilter = blankToNull(family);
        return tasks.search(warehouseId, statusFilter, familyFilter, equipmentId, PageRequest.of(0, capped)).stream()
                .map(DeviceTaskView::from)
                .toList();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
