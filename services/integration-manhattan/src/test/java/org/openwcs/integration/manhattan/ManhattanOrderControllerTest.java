package org.openwcs.integration.manhattan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.openwcs.integration.manhattan.client.HostApiClient;
import org.openwcs.integration.manhattan.client.MasterDataClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The Manhattan adapter resolves items to SKUs and translates orders into the Host API. */
@SpringBootTest
@AutoConfigureMockMvc
class ManhattanOrderControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper om;

    @MockBean
    HostApiClient hostApi;

    @MockBean
    MasterDataClient masterData;

    @Test
    @SuppressWarnings("unchecked")
    void translatesManhattanOrderToHostApi() throws Exception {
        UUID skuId = UUID.randomUUID();
        when(masterData.skuIdByCode("ITEM-7")).thenReturn(skuId);

        Map<String, Object> mh = Map.of(
                "orderId", "MO-1", "facilityId", UUID.randomUUID().toString(), "customer", "CUST",
                "serviceLevel", "STANDARD", "route", "MANCHESTER",
                "shipToAddress", Map.of("name", "Acme", "addressLine1", "1 High St",
                        "city", "Manchester", "postalCode", "M1 1AA", "countryCode", "GB"),
                "orderLines", List.of(Map.of("itemId", "ITEM-7", "quantity", 4)));

        mockMvc.perform(post("/api/integration/manhattan/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(mh)))
                .andExpect(status().isOk());

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(hostApi).createOrder(captor.capture());
        Map<String, Object> host = captor.getValue();
        assertThat(host).containsEntry("orderRef", "MO-1").containsEntry("routeCode", "MANCHESTER");
        assertThat((Map<String, Object>) host.get("shipTo")).containsEntry("line1", "1 High St");
        assertThat(((List<Map<String, Object>>) host.get("lines")).get(0)).containsEntry("skuId", skuId);
    }

    @Test
    void unknownItemIsRejected() throws Exception {
        when(masterData.skuIdByCode(any())).thenReturn(null);

        Map<String, Object> mh = Map.of(
                "orderId", "MO-2", "facilityId", UUID.randomUUID().toString(),
                "orderLines", List.of(Map.of("itemId", "NOPE", "quantity", 1)));

        mockMvc.perform(post("/api/integration/manhattan/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(mh)))
                .andExpect(status().isUnprocessableEntity());
    }
}
