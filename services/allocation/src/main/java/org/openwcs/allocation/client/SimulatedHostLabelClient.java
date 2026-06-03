package org.openwcs.allocation.client;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Default {@link HostLabelClient} that stands in for the host until the integration gateway
 * implements the real barcode-allocation call. It returns a deterministic, unique-per-shipper
 * barcode so the dispatch-label flow works end-to-end in dev/test. Replaced by an HTTP client
 * to the host (SAP/Manhattan) in the host-integration increment.
 */
@Configuration
public class SimulatedHostLabelClient {

    @Bean
    @ConditionalOnMissingBean(HostLabelClient.class)
    public HostLabelClient hostLabelClient() {
        return (orderRef, warehouseId, serviceCode, routeCode, seqNo) -> {
            String service = serviceCode == null ? "STD" : serviceCode;
            // A plausible host-style label id; unique per (order, carton).
            return "HLBL-" + service + "-" + orderRef + "-" + seqNo;
        };
    }
}
