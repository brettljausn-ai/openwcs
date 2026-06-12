package org.openwcs.process.delegate;

import java.util.UUID;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.openwcs.process.client.OrderClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * BPMN service-task delegate that releases an order for fulfilment (order-management then
 * allocates + cubes it). Reference as {@code flowable:delegateExpression="${releaseOrder}"}.
 * Variables: {@code orderId} (required); optional {@code allowShort} — when true the order is
 * short released (the supervisor decision to pick the available qty and ship short), which is
 * how a NOT_FULFILLABLE order is put back onto the happy path.
 */
@Component("releaseOrder")
public class ReleaseOrderDelegate implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(ReleaseOrderDelegate.class);

    private final OrderClient orders;

    public ReleaseOrderDelegate(OrderClient orders) {
        this.orders = orders;
    }

    @Override
    public void execute(DelegateExecution execution) {
        UUID orderId = UUID.fromString(execution.getVariable("orderId").toString());
        boolean allowShort = ProcessVariables.allowShort(execution);
        try {
            if (allowShort) {
                orders.releaseShort(orderId);
            } else {
                orders.release(orderId);
            }
        } catch (RuntimeException e) {
            log.error("releaseOrder failed for order {} (process instance {}, allowShort {}): {}",
                    orderId, execution.getProcessInstanceId(), allowShort, e.toString());
            throw e;
        }
        log.info("order {} {} for fulfilment by process {} (instance {}): order-management will allocate and cube it",
                orderId, allowShort ? "SHORT released" : "released",
                execution.getProcessInstanceBusinessKey(), execution.getProcessInstanceId());
    }
}
