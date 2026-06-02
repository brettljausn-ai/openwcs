package org.openwcs.masterdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.List;
import java.util.Map;
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

/** Covers the label-template catalog: create with elements, then render to ZPL and PDF. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class LabelTemplateApiTest {

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

    private String createTemplate() throws Exception {
        Map<String, Object> body = Map.of(
                "code", "SHIP-4X6-" + java.util.UUID.randomUUID(), "name", "Shipping 4x6",
                "widthMm", 101.6, "heightMm", 152.4, "dpi", 203,
                "elements", List.of(
                        Map.of("type", "TEXT", "key", "shipToName", "xMm", 5, "yMm", 5, "fontPt", 12),
                        Map.of("type", "BARCODE", "key", "trackingBarcode", "xMm", 5, "yMm", 40,
                                "heightMm", 15, "barcodeSymbology", "CODE128")));
        String created = mockMvc.perform(post("/api/master-data/label-templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andReturn().getResponse().getContentAsString();
        return om.readTree(created).get("id").asText();
    }

    @Test
    void rendersTemplateToZpl() throws Exception {
        String id = createTemplate();
        String response = mockMvc.perform(post("/api/master-data/label-templates/" + id + "/render")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "format", "ZPL",
                                "data", Map.of("shipToName", "Acme Ltd", "trackingBarcode", "1Z999AA")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.format").value("ZPL"))
                .andReturn().getResponse().getContentAsString();

        String zpl = new String(Base64.getDecoder().decode(om.readTree(response).get("payloadBase64").asText()));
        assertThat(zpl).startsWith("^XA").contains("^XZ").contains("Acme Ltd").contains("1Z999AA").contains("^BC");
    }

    @Test
    void rendersTemplateToPdf() throws Exception {
        String id = createTemplate();
        String response = mockMvc.perform(post("/api/master-data/label-templates/" + id + "/render")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "format", "PDF",
                                "data", Map.of("shipToName", "Acme Ltd", "trackingBarcode", "1Z999AA")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.format").value("PDF"))
                .andReturn().getResponse().getContentAsString();

        byte[] pdf = Base64.getDecoder().decode(om.readTree(response).get("payloadBase64").asText());
        assertThat(new String(pdf)).startsWith("%PDF-1.4").contains("startxref").endsWith("%%EOF");
    }

    @Test
    void rejectsUnsupportedFormat() throws Exception {
        String id = createTemplate();
        mockMvc.perform(post("/api/master-data/label-templates/" + id + "/render")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("format", "XML", "data", Map.of()))))
                .andExpect(status().isBadRequest());
    }
}
