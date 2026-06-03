package org.openwcs.integration.sap;

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
import org.openwcs.integration.sap.client.HostApiClient;
import org.openwcs.integration.sap.client.MasterDataClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The SAP adapter resolves materials to SKUs and translates SAP orders into the Host API. */
@SpringBootTest
@AutoConfigureMockMvc
class SapOrderControllerTest {

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
    void translatesSapOrderToHostApi() throws Exception {
        UUID skuId = UUID.randomUUID();
        when(masterData.skuIdByCode("MAT-100")).thenReturn(skuId);

        Map<String, Object> sap = Map.of(
                "salesOrder", "SO-1", "warehouseId", UUID.randomUUID().toString(), "soldTo", "CUST-9",
                "serviceCode", "EXPRESS", "routeCode", "CENTRAL_LONDON",
                "shipTo", Map.of("name", "Acme", "street", "1 High St", "city", "London",
                        "postalCode", "EC1A 1AA", "country", "GB"),
                "items", List.of(Map.of("material", "MAT-100", "quantity", 5)));

        mockMvc.perform(post("/api/integration/sap/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(sap)))
                .andExpect(status().isOk());

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(hostApi).createOrder(captor.capture());
        Map<String, Object> host = captor.getValue();
        assertThat(host).containsEntry("orderRef", "SO-1").containsEntry("serviceCode", "EXPRESS");
        assertThat((Map<String, Object>) host.get("shipTo")).containsEntry("line1", "1 High St");
        List<Map<String, Object>> lines = (List<Map<String, Object>>) host.get("lines");
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).containsEntry("skuId", skuId);
    }

    @Test
    void unknownMaterialIsRejected() throws Exception {
        when(masterData.skuIdByCode(any())).thenReturn(null);

        Map<String, Object> sap = Map.of(
                "salesOrder", "SO-2", "warehouseId", UUID.randomUUID().toString(),
                "items", List.of(Map.of("material", "NOPE", "quantity", 1)));

        mockMvc.perform(post("/api/integration/sap/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(sap)))
                .andExpect(status().isUnprocessableEntity());
    }
}
