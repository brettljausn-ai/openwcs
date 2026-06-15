package org.openwcs.flow.service;

import java.util.HashMap;
import java.util.Map;
import org.openwcs.flow.api.DeviceTaskView;
import org.openwcs.flow.api.FlowMoveRequest;
import org.openwcs.flow.api.FlowMoveResult;
import org.openwcs.flow.api.RequestDeviceTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Generic physical-move dispatch (execution-layer item 1): moves a handling unit from one location
 * to another by dispatching the device task(s) that physically carry it.
 *
 * <p>v1 issues a single RELOCATE through the existing device-task machinery: AUTOSTORE maps to
 * BIN_RELOCATE, every other family to RELOCATE (ADR-0009), both of which the equipment emulator
 * already simulates. The dispatch is tagged {@code moveSource=flow-move} in the payload so its
 * completion books the HU's new location in inventory (see
 * {@link DeviceTaskService#bookFlowMoveLocation}) WITHOUT touching the induction dig-out path that
 * shares the RELOCATE command.
 *
 * <p>A documented follow-up is a multi-leg RETRIEVE → CONVEY → STORE across systems (e.g. AutoStore
 * port out, convey, ASRS store) when the from/to locations live on different subsystems.
 */
@Service
public class FlowMoveService {

    private static final Logger log = LoggerFactory.getLogger(FlowMoveService.class);

    private static final String DEFAULT_FAMILY = "AUTOSTORE";

    private final DeviceTaskService deviceTasks;

    public FlowMoveService(DeviceTaskService deviceTasks) {
        this.deviceTasks = deviceTasks;
    }

    public FlowMoveResult move(FlowMoveRequest request, String actor) {
        String family = (request.family() == null || request.family().isBlank())
                ? DEFAULT_FAMILY : request.family().trim().toUpperCase();
        String command = "AUTOSTORE".equals(family) ? "BIN_RELOCATE" : "RELOCATE";

        Map<String, Object> payload = new HashMap<>();
        payload.put("huId", request.huId());
        payload.put("huCode", request.huCode());
        payload.put("fromLocationId", request.fromLocationId());
        payload.put("toLocationId", request.toLocationId());
        if (request.reason() != null) {
            payload.put("reason", request.reason());
        }
        // Tag the dispatch so DeviceTaskService books the HU's new location on completion and the
        // induction dig-out relocate path stays a no-op for it.
        payload.put(DeviceTaskService.MOVE_SOURCE_KEY, DeviceTaskService.MOVE_SOURCE_FLOW);

        log.info("flow move: hu {} ({}) from location {} to {} via {} {} (actor {}, reason {})",
                request.huId(), request.huCode(), request.fromLocationId(), request.toLocationId(),
                family, command, actor, request.reason());

        RequestDeviceTask deviceRequest = new RequestDeviceTask(
                request.warehouseId(), family, null, command, payload, null);
        DeviceTaskView view = deviceTasks.request(deviceRequest, actor);
        return new FlowMoveResult(view.id());
    }
}
