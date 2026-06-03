package org.openwcs.integration.sap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openwcs.integration.sap.client.MasterDataClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The route feed upserts each host route into master-data and reports a created/updated summary. */
@SpringBootTest
@AutoConfigureMockMvc
class RouteFeedControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper om;

    @MockBean
    MasterDataClient masterData;

    @Test
    void syncsRoutesAndReportsCreatedUpdated() throws Exception {
        when(masterData.upsertRoute(any())).thenReturn(
                MasterDataClient.UpsertResult.CREATED, MasterDataClient.UpsertResult.UPDATED);

        String body = om.writeValueAsString(List.of(
                Map.of("code", "CENTRAL_LONDON", "name", "Central London", "region", "London", "hostRef", "R-1"),
                Map.of("code", "MANCHESTER", "name", "Manchester", "region", "NW", "hostRef", "R-2")));

        mockMvc.perform(post("/api/integration/sap/routes/sync")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(2))
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.updated").value(1));

        verify(masterData, times(2)).upsertRoute(any());
    }
}
