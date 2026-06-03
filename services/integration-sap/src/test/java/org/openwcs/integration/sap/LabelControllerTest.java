package org.openwcs.integration.sap;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The host label-barcode endpoint returns a unique-per-shipper barcode (simulated SAP). */
@SpringBootTest
@AutoConfigureMockMvc
class LabelControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper om;

    @Test
    void allocatesABarcodePerShipper() throws Exception {
        mockMvc.perform(post("/api/integration/sap/labels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "orderRef", "ORD-1", "warehouseId", UUID.randomUUID(),
                                "serviceCode", "EXPRESS", "routeCode", "CENTRAL_LONDON", "seqNo", 2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.barcode").value("SAPEXPRESS-ORD-1-2"));
    }
}
