package org.openwcs.integration.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.openwcs.integration.host.client.TxLogClient;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

/** Host inventory adjustment is appended to the txlog as a StockAdjusted event. */
class HostInventoryControllerTest extends AbstractHostIntegrationTest {

    @MockBean
    TxLogClient txLog;

    @Test
    @SuppressWarnings("unchecked")
    void adjustmentBecomesStockAdjustedEvent() throws Exception {
        when(txLog.append(any(), eq("StockAdjusted"), any(), any()))
                .thenReturn(new TxLogClient.Appended("ev-1", 9));

        UUID sku = UUID.randomUUID();
        Map<String, Object> body = Map.of(
                "warehouseId", UUID.randomUUID().toString(),
                "skuId", sku.toString(),
                "locationId", UUID.randomUUID().toString(),
                "qtyDelta", -3, "uomCode", "EACH", "reason", "cycle count");

        mockMvc.perform(post("/api/host/inventory/adjustments")
                        .header("X-Auth-User", "host-bridge")
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.position").value(9));

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(txLog).append(eq(sku.toString()), eq("StockAdjusted"), eq("host-bridge"), payload.capture());
        assertThat(payload.getValue()).containsEntry("uomCode", "EACH").containsKey("qtyDelta");
    }
}
