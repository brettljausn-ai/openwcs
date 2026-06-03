package org.openwcs.process.delegate;

import java.util.UUID;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.openwcs.process.client.OrderClient;
import org.springframework.stereotype.Component;

/**
 * BPMN service-task delegate that releases an order for fulfilment (order-management then
 * allocates + cubes it). Reference as {@code flowable:delegateExpression="${releaseOrder}"}.
 * Variable: {@code orderId}.
 */
@Component("releaseOrder")
public class ReleaseOrderDelegate implements JavaDelegate {

    private final OrderClient orders;

    public ReleaseOrderDelegate(OrderClient orders) {
        this.orders = orders;
    }

    @Override
    public void execute(DelegateExecution execution) {
        orders.release(UUID.fromString(execution.getVariable("orderId").toString()));
    }
}
