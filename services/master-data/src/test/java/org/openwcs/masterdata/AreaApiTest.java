package org.openwcs.masterdata;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * End-to-end test of the Areas REST layer (controllers + V19 migration + recursive CTEs): area
 * CRUD, nesting, the cycle guard, recursive location-id expansion, and resolve-by-code.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AreaApiTest {

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

    private String createWarehouse(String code) throws Exception {
        String wh = mockMvc.perform(post("/api/master-data/warehouses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("code", code, "name", code + " DC"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return om.readTree(wh).get("id").asText();
    }

    private String createArea(String warehouseId, String code, String parentAreaId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("warehouseId", warehouseId);
        body.put("code", code);
        body.put("name", code + " zone");
        if (parentAreaId != null) {
            body.put("parentAreaId", parentAreaId);
        }
        String area = mockMvc.perform(post("/api/master-data/areas")
                        .header("X-Auth-Roles", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return om.readTree(area).get("id").asText();
    }

    private String createLocationInArea(String warehouseId, String code, String areaId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("warehouseId", warehouseId);
        body.put("code", code);
        body.put("locationType", "BIN");
        body.put("purpose", "STORAGE");
        body.put("areaId", areaId);
        String loc = mockMvc.perform(post("/api/master-data/locations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.areaId").value(areaId))
                .andReturn().getResponse().getContentAsString();
        return om.readTree(loc).get("id").asText();
    }

    @Test
    void areaCrudAndNesting() throws Exception {
        String wh = createWarehouse("WH-AREA");
        String root = createArea(wh, "ZONE-A", null);

        mockMvc.perform(get("/api/master-data/areas/{id}", root))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("ZONE-A"))
                .andExpect(jsonPath("$.parentAreaId").value(org.hamcrest.Matchers.nullValue()));

        // Nesting: create a child area under the root.
        String child = createArea(wh, "ZONE-A-1", root);
        mockMvc.perform(get("/api/master-data/areas/{id}", child))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parentAreaId").value(root));

        // Filter by parent returns only direct children.
        mockMvc.perform(get("/api/master-data/areas")
                        .param("warehouseId", wh)
                        .param("parentAreaId", root))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].code").value("ZONE-A-1"));

        // List all areas in the warehouse.
        mockMvc.perform(get("/api/master-data/areas").param("warehouseId", wh))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // Update the name.
        mockMvc.perform(put("/api/master-data/areas/{id}", root)
                        .header("X-Auth-Roles", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "warehouseId", wh, "code", "ZONE-A", "name", "Renamed zone"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed zone"));

        // Archive (delete) is admin-gated and returns 204.
        mockMvc.perform(delete("/api/master-data/areas/{id}", child).header("X-Auth-Roles", "ADMIN"))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/master-data/areas/{id}", child))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
    }

    @Test
    void writesAreAdminGated() throws Exception {
        String wh = createWarehouse("WH-AREA-RBAC");
        mockMvc.perform(post("/api/master-data/areas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("warehouseId", wh, "code", "NO-ADMIN"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void cycleGuardRejectsParentThatIsADescendant() throws Exception {
        String wh = createWarehouse("WH-CYCLE");
        String a = createArea(wh, "A", null);
        String b = createArea(wh, "B", a);
        String c = createArea(wh, "C", b);

        // Making A's parent = C would make A its own ancestor (A -> B -> C -> A): reject with 400.
        mockMvc.perform(put("/api/master-data/areas/{id}", a)
                        .header("X-Auth-Roles", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "warehouseId", wh, "code", "A", "parentAreaId", c))))
                .andExpect(status().isBadRequest());

        // Self-parenting is also rejected.
        mockMvc.perform(put("/api/master-data/areas/{id}", a)
                        .header("X-Auth-Roles", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "warehouseId", wh, "code", "A", "parentAreaId", a))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void parentInDifferentWarehouseIsRejected() throws Exception {
        String wh1 = createWarehouse("WH-X1");
        String wh2 = createWarehouse("WH-X2");
        String parentInWh1 = createArea(wh1, "P", null);

        mockMvc.perform(post("/api/master-data/areas")
                        .header("X-Auth-Roles", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "warehouseId", wh2, "code", "CHILD", "parentAreaId", parentInWh1))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void locationIdsRecursiveReturnsDescendantAreaLocations() throws Exception {
        String wh = createWarehouse("WH-LOC-IDS");
        String root = createArea(wh, "R", null);
        String child = createArea(wh, "R-1", root);
        String grandchild = createArea(wh, "R-1-1", child);

        String locRoot = createLocationInArea(wh, "L-ROOT", root);
        String locChild = createLocationInArea(wh, "L-CHILD", child);
        String locGrandchild = createLocationInArea(wh, "L-GC", grandchild);

        // Recursive (default): root + all descendants -> all three locations.
        String recursive = mockMvc.perform(get("/api/master-data/areas/{id}/location-ids", root))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(om.readValue(recursive, String[].class))
                .containsExactlyInAnyOrder(locRoot, locChild, locGrandchild);

        // Non-recursive: only the root area's directly-assigned location.
        mockMvc.perform(get("/api/master-data/areas/{id}/location-ids", root).param("recursive", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0]").value(locRoot));

        // location-ids on an unknown area is a 404.
        mockMvc.perform(get("/api/master-data/areas/{id}/location-ids", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void resolveByCodeIsCaseInsensitive() throws Exception {
        String wh = createWarehouse("WH-RESOLVE");
        String area = createArea(wh, "PACKING", null);

        mockMvc.perform(get("/api/master-data/areas/resolve")
                        .param("warehouseId", wh)
                        .param("ref", "packing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.area.id").value(area))
                .andExpect(jsonPath("$.area.code").value("PACKING"));

        // A miss returns found:false with a null area (still HTTP 200).
        mockMvc.perform(get("/api/master-data/areas/resolve")
                        .param("warehouseId", wh)
                        .param("ref", "NOPE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(false))
                .andExpect(jsonPath("$.area").value(org.hamcrest.Matchers.nullValue()));
    }
}
