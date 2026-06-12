package org.openwcs.masterdata;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Admin database console: ADMIN-only schema browsing and strictly read-only SELECT execution.
 * The safety model is layered (mirrors {@code AdminDbService}): a validator rejects everything
 * that is not a single SELECT, and a read-only transaction stops any write that slips past it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AdminDbConsoleTest {

    private static final String ROLES = "X-Auth-Roles";

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
    DataSource dataSource;

    @BeforeEach
    void sequenceForSmuggleTest() throws Exception {
        // A sequence whose nextval() is a WRITE that the SELECT-only validator cannot see.
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute("CREATE SEQUENCE IF NOT EXISTS master_data.admin_db_smuggle_seq");
        }
    }

    private MockHttpServletRequestBuilder query(String body, String roles) {
        MockHttpServletRequestBuilder req = post("/api/master-data/admin/db/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        return roles == null ? req : req.header(ROLES, roles);
    }

    // ------------------------------------------------------------------ RBAC

    @Test
    void schemasRequireAdmin() throws Exception {
        mockMvc.perform(get("/api/master-data/admin/db/schemas"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/master-data/admin/db/schemas").header(ROLES, "VIEWER,OPERATOR,SUPERVISOR"))
                .andExpect(status().isForbidden());
    }

    @Test
    void queryRequiresAdmin() throws Exception {
        mockMvc.perform(query("{\"sql\":\"select 1\"}", null))
                .andExpect(status().isForbidden());
        mockMvc.perform(query("{\"sql\":\"select 1\"}", "SUPERVISOR"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ schema metadata

    @Test
    void adminListsSchemasWithTablesAndColumns() throws Exception {
        mockMvc.perform(get("/api/master-data/admin/db/schemas").header(ROLES, "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItem("master_data")))
                .andExpect(jsonPath("$[?(@.name=='master_data')].tables[*].name", hasItem("sku")))
                .andExpect(jsonPath("$[?(@.name=='master_data')].tables[*].columns[*].name", hasItem("sku_id")))
                // system schemas are never exposed
                .andExpect(jsonPath("$[?(@.name=='pg_catalog')]").isEmpty())
                .andExpect(jsonPath("$[?(@.name=='information_schema')]").isEmpty());
    }

    // ------------------------------------------------------------------ happy path

    @Test
    void adminRunsSelect() throws Exception {
        mockMvc.perform(query("{\"sql\":\"select 1 as one, 'a' as letter\"}", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[0].name").value("one"))
                .andExpect(jsonPath("$.columns[1].name").value("letter"))
                .andExpect(jsonPath("$.rows[0][0]").value(1))
                .andExpect(jsonPath("$.rows[0][1]").value("a"))
                .andExpect(jsonPath("$.rowCount").value(1))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.executionMs", greaterThanOrEqualTo(0)));
    }

    @Test
    void commentsTrailingSemicolonAndCteAreFine() throws Exception {
        mockMvc.perform(query(
                "{\"sql\":\"-- a comment\\nwith nums as (select generate_series(1, 3) n) select count(*) from nums;\"}",
                "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0][0]").value(3));
    }

    // ------------------------------------------------------------------ row cap

    @Test
    void rowCapTruncates() throws Exception {
        mockMvc.perform(query("{\"sql\":\"select * from generate_series(1, 50)\",\"maxRows\":10}", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowCount").value(10))
                .andExpect(jsonPath("$.truncated").value(true));

        // default cap is 200; the hard maximum is 1000 even when more is requested
        mockMvc.perform(query("{\"sql\":\"select * from generate_series(1, 5000)\"}", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowCount").value(200))
                .andExpect(jsonPath("$.truncated").value(true));
        mockMvc.perform(query("{\"sql\":\"select * from generate_series(1, 5000)\",\"maxRows\":99999}", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowCount").value(1000))
                .andExpect(jsonPath("$.truncated").value(true));
    }

    // ------------------------------------------------------------------ write rejection (validator)

    @Test
    void updateIsRejected() throws Exception {
        mockMvc.perform(query("{\"sql\":\"update master_data.sku set name = 'oops'\"}", "ADMIN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("SELECT")));
    }

    @Test
    void insertIsRejected() throws Exception {
        mockMvc.perform(query("{\"sql\":\"insert into master_data.sku (id) values (gen_random_uuid())\"}", "ADMIN"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteDropAndExplainAreRejected() throws Exception {
        for (String sql : new String[] {
                "delete from master_data.sku",
                "drop table master_data.sku",
                "truncate master_data.sku",
                "explain select * from master_data.sku",
                "do $$ begin null; end $$",
                "copy master_data.sku to stdout"}) {
            mockMvc.perform(query("{\"sql\":\"" + sql.replace("\"", "\\\"") + "\"}", "ADMIN"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void multiStatementIsRejected() throws Exception {
        mockMvc.perform(query("{\"sql\":\"select 1; select 2\"}", "ADMIN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("one SELECT")));
        mockMvc.perform(query("{\"sql\":\"select 1; delete from master_data.sku\"}", "ADMIN"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void dataModifyingCteIsRejected() throws Exception {
        mockMvc.perform(query(
                "{\"sql\":\"with x as (delete from master_data.sku returning *) select * from x\"}", "ADMIN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("DELETE")));
    }

    // ------------------------------------------------------------------ write rejection (database)

    @Test
    void writeSmuggledPastTheValidatorFailsOnTheReadOnlyTransaction() throws Exception {
        // nextval() writes to the sequence but parses as an innocent SELECT — the validator
        // cannot catch it, so the READ ONLY transaction must.
        mockMvc.perform(query("{\"sql\":\"select nextval('master_data.admin_db_smuggle_seq')\"}", "ADMIN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("read-only")));
    }

    @Test
    void sqlErrorsSurfaceThePostgresMessage() throws Exception {
        mockMvc.perform(query("{\"sql\":\"select * from master_data.does_not_exist\"}", "ADMIN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("does_not_exist")));
    }
}
