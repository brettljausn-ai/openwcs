package org.openwcs.integration.host.api;

import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.openwcs.integration.host.client.OrderManagementClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The canonical, vendor-neutral openWCS Host API for inbound requests: a host pushes outbound
 * orders and ASNs here, and they are translated to order-management orders. Confirmations flow
 * back via {@link ConfirmationController}.
 */
@RestController
@RequestMapping("/api/host")
public class HostController {

    private static final Logger log = LoggerFactory.getLogger(HostController.class);

    private final OrderManagementClient orders;

    public HostController(OrderManagementClient orders) {
        this.orders = orders;
    }

    @PostMapping("/orders")
    public OrderManagementClient.CreatedOrder createOrder(@Valid @RequestBody CreateHostOrder request) {
        Map<String, Object> body = new HashMap<>();
        body.put("orderRef", request.orderRef());
        body.put("warehouseId", request.warehouseId());
        body.put("orderType", "OUTBOUND");
        body.put("customerRef", request.customerRef());
        body.put("priority", request.priority());
        body.put("dispatchBy", request.dispatchBy());
        body.put("serviceCode", request.serviceCode());
        body.put("routeCode", request.routeCode());
        body.put("shipTo", request.shipTo());
        body.put("labelTemplateCode", request.labelTemplateCode());
        body.put("lines", lines(request.lines()));
        try {
            OrderManagementClient.CreatedOrder created = orders.createOrder(body);
            log.info("host order {} accepted: {} lines for customer {} -> order-management order {} (status {})",
                    request.orderRef(), request.lines().size(), request.customerRef(),
                    created.id(), created.status());
            return created;
        } catch (RuntimeException e) {
            log.warn("host order {} rejected: order-management refused the translated order ({} lines): {}",
                    request.orderRef(), request.lines().size(), e.toString());
            throw e;
        }
    }

    @PostMapping("/asns")
    public OrderManagementClient.CreatedOrder createAsn(@Valid @RequestBody CreateAsn request) {
        Map<String, Object> body = new HashMap<>();
        body.put("orderRef", request.asnRef());
        body.put("warehouseId", request.warehouseId());
        body.put("orderType", "INBOUND");
        body.put("customerRef", request.supplierRef());
        body.put("lines", asnLines(request.lines()));
        try {
            OrderManagementClient.CreatedOrder created = orders.createOrder(body);
            log.info("host ASN {} accepted: {} lines from supplier {} -> order-management inbound order {} (status {})",
                    request.asnRef(), request.lines().size(), request.supplierRef(),
                    created.id(), created.status());
            return created;
        } catch (RuntimeException e) {
            log.warn("host ASN {} rejected: order-management refused the translated inbound order ({} lines): {}",
                    request.asnRef(), request.lines().size(), e.toString());
            throw e;
        }
    }

    private static List<Map<String, Object>> lines(List<CreateHostOrder.Line> lines) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CreateHostOrder.Line l : lines) {
            out.add(Map.of("skuId", l.skuId(), "qty", l.qty()));
        }
        return out;
    }

    private static List<Map<String, Object>> asnLines(List<CreateAsn.Line> lines) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CreateAsn.Line l : lines) {
            out.add(Map.of("skuId", l.skuId(), "qty", l.qty()));
        }
        return out;
    }
}
