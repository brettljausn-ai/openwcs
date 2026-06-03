package org.openwcs.process.delegate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.openwcs.process.client.AllocationClient;
import org.springframework.stereotype.Component;

/**
 * BPMN service-task delegate that allocates (and cubes) an outbound order via the allocation
 * service, then writes the result back as process variables for the downstream gateway. Reference
 * it as {@code flowable:delegateExpression="${allocateOrder}"}.
 *
 * <p>Expects: {@code orderRef}, {@code warehouseId} (required); optional {@code lines} (a List of
 * maps each with {@code lineNo}, {@code skuId}, {@code qty}). Sets: {@code allocationStatus}
 * ({@code FULFILLABLE} | {@code NOT_FULFILLABLE}) and {@code shipperCount}.
 */
@Component("allocateOrder")
public class AllocateOrderDelegate implements JavaDelegate {

    private final AllocationClient allocation;

    public AllocateOrderDelegate(AllocationClient allocation) {
        this.allocation = allocation;
    }

    @Override
    public void execute(DelegateExecution execution) {
        String orderRef = execution.getVariable("orderRef").toString();
        UUID warehouseId = asUuid(execution.getVariable("warehouseId"));
        List<AllocationClient.Line> lines = readLines(execution.getVariable("lines"));

        AllocationClient.Allocation result = allocation.allocate(orderRef, warehouseId, lines);

        execution.setVariable("allocationStatus", result.status());
        execution.setVariable("shipperCount", result.shipperCount());
    }

    @SuppressWarnings("unchecked")
    private static List<AllocationClient.Line> readLines(Object raw) {
        List<AllocationClient.Line> lines = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object element : list) {
                if (element instanceof Map<?, ?> map) {
                    Map<String, Object> line = (Map<String, Object>) map;
                    lines.add(new AllocationClient.Line(
                            asInt(line.get("lineNo")),
                            asUuid(line.get("skuId")),
                            asBigDecimal(line.get("qty"))));
                }
            }
        }
        return lines;
    }

    private static int asInt(Object value) {
        if (value == null) {
            return 0;
        }
        return value instanceof Number number ? number.intValue() : Integer.parseInt(value.toString());
    }

    private static BigDecimal asBigDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
    }

    private static UUID asUuid(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof UUID uuid ? uuid : UUID.fromString(value.toString());
    }
}
