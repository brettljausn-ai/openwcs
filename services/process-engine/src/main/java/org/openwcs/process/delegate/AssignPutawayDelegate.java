package org.openwcs.process.delegate;

import java.util.UUID;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.openwcs.process.client.SlottingClient;
import org.springframework.stereotype.Component;

/**
 * BPMN service-task delegate that asks the slotting service where to put away a handling unit, and
 * writes the chosen destination back as process variables for the downstream move task. Reference
 * it as {@code flowable:delegateExpression="${assignPutaway}"}.
 *
 * <p>Expects: {@code warehouseId}, {@code skuId} (required); optional {@code huId}, {@code batchId},
 * {@code uomId}, {@code qty}, {@code blockId}. Sets: {@code targetLocationId}, {@code putawayMode},
 * {@code putawayBlockId}.
 */
@Component("assignPutaway")
public class AssignPutawayDelegate implements JavaDelegate {

    private final SlottingClient slotting;

    public AssignPutawayDelegate(SlottingClient slotting) {
        this.slotting = slotting;
    }

    @Override
    public void execute(DelegateExecution execution) {
        UUID warehouseId = asUuid(execution.getVariable("warehouseId"));
        UUID skuId = asUuid(execution.getVariable("skuId"));
        UUID huId = asUuid(execution.getVariable("huId"));
        UUID batchId = asUuid(execution.getVariable("batchId"));
        UUID uomId = asUuid(execution.getVariable("uomId"));
        UUID blockId = asUuid(execution.getVariable("blockId"));
        Object qty = execution.getVariable("qty");

        SlottingClient.Putaway decision =
                slotting.assignPutaway(warehouseId, huId, skuId, batchId, uomId, qty, blockId);

        execution.setVariable("targetLocationId", decision.locationId());
        execution.setVariable("putawayMode", decision.mode());
        execution.setVariable("putawayBlockId", decision.blockId());
    }

    private static UUID asUuid(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof UUID uuid ? uuid : UUID.fromString(value.toString());
    }
}
