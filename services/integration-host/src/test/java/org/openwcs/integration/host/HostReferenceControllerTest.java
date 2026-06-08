package org.openwcs.integration.host;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openwcs.integration.host.client.MasterDataClient;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

/** Host SKU intake syncs a batch (with UoMs and barcodes) into master-data and reports the outcome. */
class HostReferenceControllerTest extends AbstractHostIntegrationTest {

    @MockBean
    MasterDataClient masterData;

    @Test
    void syncsSkusWithUomsAndBarcodes() throws Exception {
        when(masterData.syncSkus(any())).thenReturn(new MasterDataClient.SyncReport(
                1, 1, 0, List.of(new MasterDataClient.SkuResult(
                        "SKU-1", MasterDataClient.Action.CREATED, 2, 1))));

        var sku = Map.of(
                "code", "SKU-1",
                "description", "Widget",
                "batchTracked", true,
                "uoms", List.of(
                        Map.of("code", "EACH", "baseUnit", true),
                        Map.of("code", "CASE", "parentCode", "EACH", "qtyInParent", 12)),
                "barcodes", List.of(
                        Map.of("value", "4006381333931", "uomCode", "EACH", "type", "EAN13")));

        mockMvc.perform(post("/api/host/masterdata/skus")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(List.of(sku))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(1))
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.results[0].code").value("SKU-1"))
                .andExpect(jsonPath("$.results[0].action").value("CREATED"))
                .andExpect(jsonPath("$.results[0].uoms").value(2))
                .andExpect(jsonPath("$.results[0].barcodes").value(1));
    }

    @Test
    void rejectsSkuWithoutCode() throws Exception {
        mockMvc.perform(post("/api/host/masterdata/skus")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(List.of(Map.of("description", "no code")))))
                .andExpect(status().isBadRequest());
    }
}
