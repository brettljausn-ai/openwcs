package org.openwcs.slotting;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.slotting.client.InventoryClient;
import org.openwcs.slotting.client.InventoryClient.HandlingUnitView;
import org.openwcs.slotting.client.MasterDataClient;
import org.openwcs.slotting.client.MasterDataClient.CellLocation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Dig-out planning contract (ADR 0009): a retrieve at cell Z N is blocked by HUs at cell Z &lt; N in
 * the same channel; each blocker relocates to a free location on the same cell Y (no lift move),
 * preferring same aisle, then same side, then shallow targets — never into the channel being
 * cleared, never the same target twice within one plan. Steps come front-most blocker first.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RelocationPlanApiTest {

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

    @MockBean
    MasterDataClient masterData;

    @MockBean
    InventoryClient inventory;

    final UUID wh = UUID.randomUUID();

    private static CellLocation cell(UUID id, String aisle, String side, Integer x, Integer y, Integer z) {
        return new CellLocation(id, null, "L-" + id.toString().substring(0, 8), "STORAGE", "ACTIVE",
                aisle, side, x, y, z);
    }

    private static HandlingUnitView hu(UUID huId, String code, UUID locationId) {
        return new HandlingUnitView(huId, code, locationId, "ACTIVE");
    }

    private String body(UUID warehouseId, UUID locationId) throws Exception {
        return om.writeValueAsString(Map.of(
                "warehouseId", warehouseId.toString(),
                "locationId", locationId.toString()));
    }

    @Test
    void singleBlockerRelocatesToFreeSameLevelSlot() throws Exception {
        UUID deep = UUID.randomUUID();   // the retrieve's source, Z2
        UUID face = UUID.randomUUID();   // blocker at Z1, same channel
        UUID freeSameY = UUID.randomUUID();   // free, same aisle, same Y → the target
        UUID freeOtherY = UUID.randomUUID();  // free but other level → never (lift move)
        UUID blockerHu = UUID.randomUUID();

        when(masterData.location(deep)).thenReturn(cell(deep, "A1", "LEFT", 5, 2, 2));
        when(masterData.locations(wh)).thenReturn(List.of(
                cell(deep, "A1", "LEFT", 5, 2, 2),
                cell(face, "A1", "LEFT", 5, 2, 1),
                cell(freeSameY, "A1", "LEFT", 6, 2, 1),
                cell(freeOtherY, "A1", "LEFT", 6, 3, 1)));
        when(inventory.handlingUnits(wh)).thenReturn(List.of(
                hu(UUID.randomUUID(), "HU-TARGET", deep),
                hu(blockerHu, "HU-BLOCKER", face)));

        mockMvc.perform(post("/api/slotting/relocation-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(wh, deep)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps.length()").value(1))
                .andExpect(jsonPath("$.steps[0].huId").value(blockerHu.toString()))
                .andExpect(jsonPath("$.steps[0].huCode").value("HU-BLOCKER"))
                .andExpect(jsonPath("$.steps[0].fromLocationId").value(face.toString()))
                .andExpect(jsonPath("$.steps[0].toLocationId").value(freeSameY.toString()))
                .andExpect(jsonPath("$.blocked").value(false));
    }

    @Test
    void twoBlockersComeFrontFirstWithDistinctTargets() throws Exception {
        UUID deepest = UUID.randomUUID(); // the retrieve's source, Z3
        UUID z1 = UUID.randomUUID();
        UUID z2 = UUID.randomUUID();
        UUID free1 = UUID.randomUUID();
        UUID free2 = UUID.randomUUID();
        UUID huZ1 = UUID.randomUUID();
        UUID huZ2 = UUID.randomUUID();

        when(masterData.location(deepest)).thenReturn(cell(deepest, "A1", "LEFT", 5, 1, 3));
        when(masterData.locations(wh)).thenReturn(List.of(
                cell(deepest, "A1", "LEFT", 5, 1, 3),
                cell(z1, "A1", "LEFT", 5, 1, 1),
                cell(z2, "A1", "LEFT", 5, 1, 2),
                cell(free1, "A1", "LEFT", 6, 1, 1),
                cell(free2, "A1", "LEFT", 7, 1, 1)));
        when(inventory.handlingUnits(wh)).thenReturn(List.of(
                hu(UUID.randomUUID(), "HU-TARGET", deepest),
                hu(huZ2, "HU-Z2", z2),
                hu(huZ1, "HU-Z1", z1)));

        // Front-most (Z1) first; the closer free cell (ΔX 1) goes to it, the next to Z2.
        mockMvc.perform(post("/api/slotting/relocation-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(wh, deepest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps.length()").value(2))
                .andExpect(jsonPath("$.steps[0].huId").value(huZ1.toString()))
                .andExpect(jsonPath("$.steps[0].fromLocationId").value(z1.toString()))
                .andExpect(jsonPath("$.steps[0].toLocationId").value(free1.toString()))
                .andExpect(jsonPath("$.steps[1].huId").value(huZ2.toString()))
                .andExpect(jsonPath("$.steps[1].fromLocationId").value(z2.toString()))
                .andExpect(jsonPath("$.steps[1].toLocationId").value(free2.toString()))
                .andExpect(jsonPath("$.blocked").value(false));
    }

    @Test
    void targetRankingPrefersSameAisleThenSameSideThenShallow() throws Exception {
        UUID deep = UUID.randomUUID();
        UUID face = UUID.randomUUID();
        UUID otherAisleShallow = UUID.randomUUID(); // Z1 but aisle A2 → loses to same-aisle
        UUID sameAisleOtherSideShallow = UUID.randomUUID(); // A1 RIGHT Z1 → loses to same side
        UUID sameAisleSameSideDeep = UUID.randomUUID();     // A1 LEFT Z2 → wins (aisle+side beat depth)

        when(masterData.location(deep)).thenReturn(cell(deep, "A1", "LEFT", 5, 2, 2));
        when(masterData.locations(wh)).thenReturn(List.of(
                cell(deep, "A1", "LEFT", 5, 2, 2),
                cell(face, "A1", "LEFT", 5, 2, 1),
                cell(otherAisleShallow, "A2", "LEFT", 5, 2, 1),
                cell(sameAisleOtherSideShallow, "A1", "RIGHT", 5, 2, 1),
                cell(sameAisleSameSideDeep, "A1", "LEFT", 9, 2, 2)));
        when(inventory.handlingUnits(wh)).thenReturn(List.of(
                hu(UUID.randomUUID(), "HU-TARGET", deep),
                hu(UUID.randomUUID(), "HU-BLOCKER", face)));

        mockMvc.perform(post("/api/slotting/relocation-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(wh, deep)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].toLocationId").value(sameAisleSameSideDeep.toString()));
    }

    @Test
    void clearChannelYieldsEmptyPlan() throws Exception {
        UUID deep = UUID.randomUUID();
        UUID face = UUID.randomUUID();

        when(masterData.location(deep)).thenReturn(cell(deep, "A1", "LEFT", 5, 2, 2));
        when(masterData.locations(wh)).thenReturn(List.of(
                cell(deep, "A1", "LEFT", 5, 2, 2),
                cell(face, "A1", "LEFT", 5, 2, 1)));
        when(inventory.handlingUnits(wh)).thenReturn(List.of(
                hu(UUID.randomUUID(), "HU-TARGET", deep))); // the face slot is empty

        mockMvc.perform(post("/api/slotting/relocation-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(wh, deep)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps.length()").value(0))
                .andExpect(jsonPath("$.blocked").value(false));
    }

    @Test
    void aisleFaceLocationNeedsNoDigOut() throws Exception {
        UUID face = UUID.randomUUID();
        when(masterData.location(face)).thenReturn(cell(face, "A1", "LEFT", 5, 2, 1));

        mockMvc.perform(post("/api/slotting/relocation-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(wh, face)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps.length()").value(0))
                .andExpect(jsonPath("$.blocked").value(false));
    }

    @Test
    void locationWithoutCellCoordinatesYieldsEmptyPlan() throws Exception {
        UUID loc = UUID.randomUUID();
        when(masterData.location(loc)).thenReturn(cell(loc, null, null, null, null, null));

        mockMvc.perform(post("/api/slotting/relocation-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(wh, loc)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps.length()").value(0))
                .andExpect(jsonPath("$.blocked").value(false));
    }

    @Test
    void unknownOccupancyYieldsEmptyPlan() throws Exception {
        UUID deep = UUID.randomUUID();
        when(masterData.location(deep)).thenReturn(cell(deep, "A1", "LEFT", 5, 2, 2));
        when(inventory.handlingUnits(any())).thenThrow(new RuntimeException("inventory down"));

        mockMvc.perform(post("/api/slotting/relocation-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(wh, deep)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps.length()").value(0))
                .andExpect(jsonPath("$.blocked").value(false));
    }

    @Test
    void noFreeSameLevelTargetReportsBlockedWithEmptySteps() throws Exception {
        UUID deep = UUID.randomUUID();
        UUID face = UUID.randomUUID();
        UUID freeWrongY = UUID.randomUUID();    // free but on another level — useless without a lift
        UUID sameYButOccupied = UUID.randomUUID();

        when(masterData.location(deep)).thenReturn(cell(deep, "A1", "LEFT", 5, 2, 2));
        when(masterData.locations(wh)).thenReturn(List.of(
                cell(deep, "A1", "LEFT", 5, 2, 2),
                cell(face, "A1", "LEFT", 5, 2, 1),
                cell(freeWrongY, "A1", "LEFT", 6, 3, 1),
                cell(sameYButOccupied, "A1", "LEFT", 6, 2, 1)));
        when(inventory.handlingUnits(wh)).thenReturn(List.of(
                hu(UUID.randomUUID(), "HU-TARGET", deep),
                hu(UUID.randomUUID(), "HU-BLOCKER", face),
                hu(UUID.randomUUID(), "HU-OTHER", sameYButOccupied)));

        mockMvc.perform(post("/api/slotting/relocation-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(wh, deep)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps.length()").value(0))
                .andExpect(jsonPath("$.blocked").value(true));
    }

    @Test
    void unknownLocationIs404() throws Exception {
        UUID unknown = UUID.randomUUID();
        when(masterData.location(unknown)).thenReturn(null);

        mockMvc.perform(post("/api/slotting/relocation-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(wh, unknown)))
                .andExpect(status().isNotFound());
    }

    @Test
    void missingFieldsAre400() throws Exception {
        mockMvc.perform(post("/api/slotting/relocation-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("warehouseId", wh.toString()))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/slotting/relocation-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
