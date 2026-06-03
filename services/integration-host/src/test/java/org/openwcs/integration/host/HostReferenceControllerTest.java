package org.openwcs.integration.host;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openwcs.integration.host.client.MasterDataClient;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

/** Host SKU intake upserts into master-data and reports created/updated. */
class HostReferenceControllerTest extends AbstractHostIntegrationTest {

    @MockBean
    MasterDataClient masterData;

    @Test
    void upsertsSku() throws Exception {
        when(masterData.upsertSku(any())).thenReturn(MasterDataClient.UpsertResult.CREATED);

        mockMvc.perform(post("/api/host/masterdata/skus")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "code", "SKU-1", "description", "Widget", "batchTracked", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SKU-1"))
                .andExpect(jsonPath("$.result").value("CREATED"));
    }

    @Test
    void rejectsSkuWithoutCode() throws Exception {
        mockMvc.perform(post("/api/host/masterdata/skus")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("description", "no code"))))
                .andExpect(status().isBadRequest());
    }
}
