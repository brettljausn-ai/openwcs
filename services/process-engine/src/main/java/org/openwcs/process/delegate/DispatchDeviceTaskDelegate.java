package org.openwcs.process.delegate;

import java.util.Map;
import java.util.UUID;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.openwcs.process.client.DeviceTaskClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * BPMN service-task delegate that originates a device task in flow-orchestrator from process
 * variables. Reference it in a process as {@code flowable:delegateExpression="${dispatchDeviceTask}"}.
 * Expects variables: {@code warehouseId}, {@code family}, {@code command} (required); optional
 * {@code equipmentId}, {@code payload} (map), {@code correlationId}.
 */
@Component("dispatchDeviceTask")
public class DispatchDeviceTaskDelegate implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(DispatchDeviceTaskDelegate.class);

    private final DeviceTaskClient deviceTasks;

    public DispatchDeviceTaskDelegate(DeviceTaskClient deviceTasks) {
        this.deviceTasks = deviceTasks;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void execute(DelegateExecution execution) {
        UUID warehouseId = asUuid(execution.getVariable("warehouseId"));
        String family = (String) execution.getVariable("family");
        String command = (String) execution.getVariable("command");
        UUID equipmentId = asUuid(execution.getVariable("equipmentId"));
        UUID correlationId = asUuid(execution.getVariable("correlationId"));
        Object payload = execution.getVariable("payload");
        Map<String, Object> payloadMap = payload instanceof Map ? (Map<String, Object>) payload : Map.of();

        try {
            deviceTasks.dispatch(warehouseId, family, equipmentId, command, payloadMap, correlationId);
        } catch (RuntimeException e) {
            log.error("dispatchDeviceTask failed: {} command {} (process instance {}, correlation {}): {}",
                    family, command, execution.getProcessInstanceId(), correlationId, e.toString());
            throw e;
        }
        log.info("device task originated by process {} (instance {}): {} command {} to equipment {} (correlation {})",
                execution.getProcessInstanceBusinessKey(), execution.getProcessInstanceId(),
                family, command, equipmentId == null ? "any" : equipmentId, correlationId);
    }

    private static UUID asUuid(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof UUID uuid ? uuid : UUID.fromString(value.toString());
    }
}
