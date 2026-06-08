package org.openwcs.slotting;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The block-policy API must round-trip the scorer weights through JSON. The entity fields
 * {@code wVelocity} … would otherwise serialize as {@code WVelocity} (bean naming keeps a leading
 * single-letter prefix capitalised), so a UI that sends/reads {@code wVelocity} would lose them on
 * save and show them blank on load. This pins the lowercase JSON contract.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class BlockPolicyApiTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper om;

    @Test
    void scorerWeightsRoundTripThroughJson() throws Exception {
        UUID block = UUID.randomUUID();
        Map<String, Object> body = new HashMap<>();
        body.put("warehouseId", UUID.randomUUID().toString());
        body.put("wVelocity", 2.5);
        body.put("wConsolidation", 0.25);
        body.put("wRedundancy", 0.75);
        body.put("wBalance", 1.5);
        body.put("defaultMaxAislePct", 0.5);
        body.put("minAislesA", 3);
        body.put("minAislesB", 2);
        body.put("minAislesC", 1);
        body.put("reslotEnabled", true);
        body.put("reslotShiftPct", 0.2);

        // PUT must accept the lowercase weight keys...
        mockMvc.perform(put("/api/slotting/block-policies/{blockId}", block)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wVelocity").value(2.5))
                .andExpect(jsonPath("$.wConsolidation").value(0.25));

        // ...and GET must echo them back under the same keys (not WVelocity).
        mockMvc.perform(get("/api/slotting/block-policies/{blockId}", block))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wVelocity").value(2.5))
                .andExpect(jsonPath("$.wConsolidation").value(0.25))
                .andExpect(jsonPath("$.wRedundancy").value(0.75))
                .andExpect(jsonPath("$.wBalance").value(1.5));
    }
}
