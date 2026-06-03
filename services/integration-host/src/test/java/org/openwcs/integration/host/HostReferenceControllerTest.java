package org.openwcs.integration.host;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openwcs.integration.host.client.MasterDataClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Host SKU intake upserts into master-data and reports created/updated. */
@SpringBootTest
@AutoConfigureMockMvc
class HostReferenceControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper om;

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
