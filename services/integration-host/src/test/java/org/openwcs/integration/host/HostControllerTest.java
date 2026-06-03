package org.openwcs.integration.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.openwcs.integration.host.client.OrderManagementClient;
import org.openwcs.integration.host.client.TxLogClient;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

/** The canonical Host API maps orders/ASNs onto order-management with the right order type. */
class HostControllerTest extends AbstractHostIntegrationTest {

    @MockBean
    OrderManagementClient orders;

    @MockBean
    TxLogClient txLog;

    @Test
    @SuppressWarnings("unchecked")
    void hostOrderBecomesOutboundOrder() throws Exception {
        when(orders.createOrder(any())).thenReturn(
                new OrderManagementClient.CreatedOrder("id-1", "ORD-1", "CREATED"));

        Map<String, Object> body = Map.of(
                "orderRef", "ORD-1", "warehouseId", UUID.randomUUID().toString(),
                "serviceCode", "EXPRESS", "routeCode", "CENTRAL_LONDON",
                "shipTo", Map.of("name", "Acme Ltd", "city", "London", "postcode", "EC1A 1AA"),
                "lines", List.of(Map.of("skuId", UUID.randomUUID().toString(), "qty", 3)));

        mockMvc.perform(post("/api/host/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderRef").value("ORD-1"));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(orders).createOrder(captor.capture());
        assertThat(captor.getValue()).containsEntry("orderType", "OUTBOUND");
        assertThat((List<?>) captor.getValue().get("lines")).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void asnBecomesInboundOrder() throws Exception {
        when(orders.createOrder(any())).thenReturn(
                new OrderManagementClient.CreatedOrder("id-2", "ASN-9", "CREATED"));

        Map<String, Object> body = Map.of(
                "asnRef", "ASN-9", "warehouseId", UUID.randomUUID().toString(),
                "lines", List.of(Map.of("skuId", UUID.randomUUID().toString(), "qty", 10)));

        mockMvc.perform(post("/api/host/asns")
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderRef").value("ASN-9"));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(orders).createOrder(captor.capture());
        assertThat(captor.getValue()).containsEntry("orderType", "INBOUND");
    }

    @Test
    void rejectsOrderWithNoLines() throws Exception {
        Map<String, Object> body = Map.of(
                "orderRef", "ORD-2", "warehouseId", UUID.randomUUID().toString(), "lines", List.of());
        mockMvc.perform(post("/api/host/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }
}
