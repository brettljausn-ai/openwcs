package org.openwcs.process.delegate;

import java.util.List;
import java.util.UUID;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.openwcs.process.client.RouteClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * BPMN service-task delegate that assigns a handling unit a conveyor route plan. Reference as
 * {@code flowable:delegateExpression="${assignRoute}"}. Variables: {@code warehouseId},
 * {@code barcode}, {@code targets} (a List of node codes).
 */
@Component("assignRoute")
public class AssignRouteDelegate implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(AssignRouteDelegate.class);

    private final RouteClient routes;

    public AssignRouteDelegate(RouteClient routes) {
        this.routes = routes;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void execute(DelegateExecution execution) {
        UUID warehouseId = UUID.fromString(execution.getVariable("warehouseId").toString());
        String barcode = (String) execution.getVariable("barcode");
        Object targets = execution.getVariable("targets");
        List<String> targetList = targets instanceof List ? (List<String>) targets : List.of();
        try {
            routes.assignRoute(warehouseId, barcode, targetList);
        } catch (RuntimeException e) {
            log.error("assignRoute failed for hu barcode {} towards {} (process instance {}): {}",
                    barcode, targetList, execution.getProcessInstanceId(), e.toString());
            throw e;
        }
        log.info("conveyor route assigned by process {} (instance {}): hu barcode {} routed to targets {}",
                execution.getProcessInstanceBusinessKey(), execution.getProcessInstanceId(),
                barcode, targetList);
    }
}
