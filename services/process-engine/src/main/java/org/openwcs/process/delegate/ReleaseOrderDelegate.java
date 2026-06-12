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
 * Variable: {@code orderId}.
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
        try {
            orders.release(orderId);
        } catch (RuntimeException e) {
            log.error("releaseOrder failed for order {} (process instance {}): {}",
                    orderId, execution.getProcessInstanceId(), e.toString());
            throw e;
        }
        log.info("order {} released for fulfilment by process {} (instance {}): order-management will allocate and cube it",
                orderId, execution.getProcessInstanceBusinessKey(), execution.getProcessInstanceId());
    }
}
