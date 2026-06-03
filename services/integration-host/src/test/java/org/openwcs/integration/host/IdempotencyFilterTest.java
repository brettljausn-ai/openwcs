package org.openwcs.integration.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.integration.host.client.OrderManagementClient;
import org.openwcs.integration.host.client.TxLogClient;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

/** A repeated Idempotency-Key replays the stored response without re-processing. */
class IdempotencyFilterTest extends AbstractHostIntegrationTest {

    @MockBean
    OrderManagementClient orders;

    @MockBean
    TxLogClient txLog;

    @Test
    void repeatedKeyReplaysResponseAndDoesNotReprocess() throws Exception {
        when(orders.createOrder(any())).thenReturn(
                new OrderManagementClient.CreatedOrder("id-1", "ORD-IDEM", "CREATED"));

        String body = om.writeValueAsString(Map.of(
                "orderRef", "ORD-IDEM", "warehouseId", UUID.randomUUID().toString(),
                "lines", List.of(Map.of("skuId", UUID.randomUUID().toString(), "qty", 1))));

        String first = mockMvc.perform(post("/api/host/orders")
                        .header("Idempotency-Key", "key-123")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String second = mockMvc.perform(post("/api/host/orders")
                        .header("Idempotency-Key", "key-123")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        // The downstream order was created only once despite two identical requests.
        verify(orders, times(1)).createOrder(any());
    }
}
