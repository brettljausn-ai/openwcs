package org.openwcs.integration.host;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openwcs.integration.host.client.OrderManagementClient;
import org.openwcs.integration.host.client.TxLogClient;
import org.springframework.boot.test.mock.mockito.MockBean;

/** Confirmations are served as a cursor feed over the transaction log. */
class ConfirmationControllerTest extends AbstractHostIntegrationTest {

    @MockBean
    TxLogClient txLog;

    @MockBean
    OrderManagementClient orders;

    @Test
    void streamsConfirmationsAndAdvancesCursor() throws Exception {
        when(txLog.feed(eq(0L), anyInt())).thenReturn(List.of(
                new TxLogClient.TxEvent(5, "ORD-1", "Picked", "2026-06-03T08:00:00Z", "op", Map.of("qty", 2)),
                new TxLogClient.TxEvent(7, "ORD-1", "Shipped", "2026-06-03T08:05:00Z", "op", Map.of())));

        mockMvc.perform(get("/api/host/confirmations").param("cursor", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmations.length()").value(2))
                .andExpect(jsonPath("$.confirmations[0].type").value("Picked"))
                .andExpect(jsonPath("$.confirmations[0].reference").value("ORD-1"))
                .andExpect(jsonPath("$.confirmations[0].cursor").value(5))
                .andExpect(jsonPath("$.nextCursor").value(7));
    }

    @Test
    void emptyFeedKeepsCursor() throws Exception {
        when(txLog.feed(eq(42L), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/host/confirmations").param("cursor", "42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmations.length()").value(0))
                .andExpect(jsonPath("$.nextCursor").value(42));
    }
}
