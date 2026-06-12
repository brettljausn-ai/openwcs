package org.openwcs.masterdata.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Read-only database console for admins (build.md: Administration → Database). All service
 * schemas live in the one shared PostgreSQL database, so the master-data datasource can browse
 * and query every schema. Safety is layered:
 * <ol>
 *   <li><b>Validation</b> — comments and literals are masked, then the statement must be a single
 *       {@code SELECT} / {@code WITH … SELECT}; extra statements after a semicolon and any
 *       data-modifying / DDL / utility keyword (INSERT, UPDATE, DELETE, COPY, DO, …) are rejected
 *       with a clear 400, including writes hidden in CTEs.</li>
 *   <li><b>Read-only transaction</b> — the query runs with autocommit off on a read-only
 *       connection after {@code SET TRANSACTION READ ONLY}, so anything smuggled past the
 *       validator still fails at the database; the transaction is always rolled back.</li>
 *   <li><b>Limits</b> — {@code SET LOCAL statement_timeout} (10 s) and a hard row cap
 *       (default 200, max 1000) with a {@code truncated} flag.</li>
 * </ol>
 * Full SQL is only ever logged at DEBUG; the INFO audit line carries user, duration and row count.
 */
@Service
public class AdminDbService {

    private static final Logger log = LoggerFactory.getLogger(AdminDbService.class);

    static final int DEFAULT_MAX_ROWS = 200;
    static final int HARD_MAX_ROWS = 1000;
    private static final int STATEMENT_TIMEOUT_MS = 10_000;

    /**
     * Keywords that may never appear anywhere in the (masked) statement: data-modifying, DDL,
     * transaction-control and utility commands. Whole-word matched outside strings, quoted
     * identifiers and comments, so writes hidden in CTEs ({@code WITH x AS (DELETE …) SELECT})
     * are caught too. Words that occur in legitimate SELECTs (END for CASE…END, FETCH FIRST,
     * SET inside no statement we allow, …) are deliberately NOT listed when they cannot execute
     * inside a single SELECT; the read-only transaction is the backstop for everything else.
     * A column that happens to share one of these names can still be queried by double-quoting it.
     */
    private static final Set<String> FORBIDDEN_KEYWORDS = Set.of(
            "insert", "update", "delete", "truncate", "merge",
            "create", "alter", "drop", "grant", "revoke", "reindex", "cluster",
            "copy", "call", "do", "execute", "prepare", "deallocate",
            "vacuum", "refresh", "lock", "listen", "notify", "unlisten",
            "discard", "checkpoint", "declare", "import",
            "begin", "commit", "rollback", "savepoint", "abort");

    private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final DataSource dataSource;

    public AdminDbService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ---------------------------------------------------------------- schema metadata

    /** One column of a table: name + PostgreSQL data type. */
    public record ColumnView(String name, String type) {
    }

    /** One table with its columns, in ordinal order. */
    public record TableView(String name, List<ColumnView> columns) {
    }

    /** One non-system schema with its tables (alphabetical). */
    public record SchemaView(String name, List<TableView> tables) {
    }

    /**
     * All non-system schemas (excluding {@code information_schema} and {@code pg_*}) with their
     * tables and column metadata from {@code information_schema}.
     */
    public List<SchemaView> listSchemas() {
        // LinkedHashMap keeps the ORDER BY ordering: schema, then table, then ordinal position.
        Map<String, Map<String, List<ColumnView>>> bySchema = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT schema_name FROM information_schema.schemata
                    WHERE schema_name <> 'information_schema' AND schema_name NOT LIKE 'pg\\_%'
                    ORDER BY schema_name""");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    bySchema.put(rs.getString(1), new LinkedHashMap<>());
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT table_schema, table_name, column_name, data_type
                    FROM information_schema.columns
                    WHERE table_schema <> 'information_schema' AND table_schema NOT LIKE 'pg\\_%'
                    ORDER BY table_schema, table_name, ordinal_position""");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    bySchema.computeIfAbsent(rs.getString(1), s -> new LinkedHashMap<>())
                            .computeIfAbsent(rs.getString(2), t -> new ArrayList<>())
                            .add(new ColumnView(rs.getString(3), rs.getString(4)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read schema metadata: " + e.getMessage(), e);
        }
        return bySchema.entrySet().stream()
                .map(s -> new SchemaView(s.getKey(),
                        s.getValue().entrySet().stream()
                                .map(t -> new TableView(t.getKey(), List.copyOf(t.getValue())))
                                .toList()))
                .toList();
    }

    // ---------------------------------------------------------------- query execution

    /** Result of one read-only query: column metadata, row values, and execution stats. */
    public record QueryResult(List<ColumnView> columns, List<List<Object>> rows, int rowCount,
                              boolean truncated, long executionMs) {
    }

    /**
     * Execute one read-only SELECT. Throws {@link IllegalArgumentException} (→ 400) for anything
     * that is not a single SELECT statement and for SQL errors, surfacing the PostgreSQL message
     * so the admin can fix the query.
     */
    public QueryResult runQuery(String sql, Integer maxRows, String user) {
        String statement = validateSelectOnly(sql);
        int cap = maxRows == null ? DEFAULT_MAX_ROWS : Math.max(1, Math.min(HARD_MAX_ROWS, maxRows));

        long start = System.nanoTime();
        log.debug("Admin DB console: user={} executing: {}", user, statement);
        try (Connection conn = dataSource.getConnection()) {
            boolean prevAutoCommit = conn.getAutoCommit();
            boolean prevReadOnly = conn.isReadOnly();
            try {
                // Defense in depth: even a write smuggled past the validator fails at the database.
                conn.setAutoCommit(false);
                conn.setReadOnly(true);
                try (Statement guard = conn.createStatement()) {
                    guard.execute("SET TRANSACTION READ ONLY");
                    guard.execute("SET LOCAL statement_timeout = " + STATEMENT_TIMEOUT_MS);
                }
                try (PreparedStatement ps = conn.prepareStatement(statement)) {
                    ps.setMaxRows(cap + 1); // +1 so we can detect truncation without fetching more
                    try (ResultSet rs = ps.executeQuery()) {
                        QueryResult result = readRows(rs, cap, start);
                        log.info("Admin DB console: user={} ran a query: {} row(s){} in {} ms",
                                user, result.rowCount(), result.truncated() ? " (truncated)" : "",
                                result.executionMs());
                        return result;
                    }
                }
            } finally {
                try {
                    conn.rollback();
                } catch (SQLException ignored) {
                    // connection is closed/broken; nothing was committed anyway
                }
                conn.setAutoCommit(prevAutoCommit);
                conn.setReadOnly(prevReadOnly);
            }
        } catch (SQLException e) {
            log.info("Admin DB console: user={} query failed after {} ms: {}",
                    user, (System.nanoTime() - start) / 1_000_000, e.getMessage());
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    private static QueryResult readRows(ResultSet rs, int cap, long startNanos) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();
        List<ColumnView> columns = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            columns.add(new ColumnView(meta.getColumnLabel(i), meta.getColumnTypeName(i)));
        }
        List<List<Object>> rows = new ArrayList<>();
        boolean truncated = false;
        while (rs.next()) {
            if (rows.size() >= cap) {
                truncated = true;
                break;
            }
            List<Object> row = new ArrayList<>(columnCount);
            for (int i = 1; i <= columnCount; i++) {
                row.add(toJsonValue(rs, i));
            }
            rows.add(row);
        }
        long executionMs = (System.nanoTime() - startNanos) / 1_000_000;
        return new QueryResult(columns, rows, rows.size(), truncated, executionMs);
    }

    /**
     * Map a result value to something JSON-friendly: numbers/booleans/strings pass through,
     * temporal types become ISO strings, and anything exotic falls back to its text rendering
     * (never blows up on a type we did not anticipate).
     */
    private static Object toJsonValue(ResultSet rs, int column) {
        Object v;
        try {
            v = rs.getObject(column);
        } catch (Exception e) {
            return safeText(rs, column);
        }
        if (v == null) {
            return null;
        }
        if (v instanceof String || v instanceof Boolean
                || v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte
                || v instanceof Double || v instanceof Float
                || v instanceof BigDecimal || v instanceof BigInteger) {
            return v;
        }
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toInstant().toString();
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate().toString();
        }
        if (v instanceof java.sql.Time t) {
            return t.toLocalTime().toString();
        }
        if (v instanceof UUID || v instanceof java.time.temporal.TemporalAccessor) {
            return v.toString();
        }
        // Arrays, json/jsonb (PGobject), intervals, geometry, bytea, … : PostgreSQL's own text
        // form is the most faithful rendering; String.valueOf is the last resort.
        Object text = safeText(rs, column);
        return text != null ? text : String.valueOf(v);
    }

    private static String safeText(ResultSet rs, int column) {
        try {
            return rs.getString(column);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- statement validation

    /**
     * Validate that {@code sql} is exactly one SELECT (or WITH … SELECT) statement and return the
     * text to execute (a harmless trailing semicolon is stripped). Anything else throws
     * {@link IllegalArgumentException} with a message the admin can act on.
     */
    static String validateSelectOnly(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("Empty query. Enter a single SELECT statement.");
        }
        String masked = maskLiteralsAndComments(sql);

        // A semicolon may only be the trailing terminator; anything after it is a second statement.
        int semicolon = masked.indexOf(';');
        if (semicolon >= 0) {
            if (!masked.substring(semicolon + 1).replace(";", " ").isBlank()) {
                throw new IllegalArgumentException(
                        "Multiple statements are not allowed: run one SELECT at a time.");
            }
            // Execute only up to the terminator so the driver sees exactly one command.
            sql = sql.substring(0, semicolon);
            masked = masked.substring(0, semicolon);
        }

        Matcher words = WORD.matcher(masked);
        String first = null;
        while (words.find()) {
            String word = words.group().toLowerCase(Locale.ROOT);
            if (first == null) {
                first = word;
                if (!first.equals("select") && !first.equals("with")) {
                    throw new IllegalArgumentException("Only SELECT queries are allowed here ('"
                            + words.group() + "' is not permitted). Start with SELECT or WITH … SELECT.");
                }
            }
            if (FORBIDDEN_KEYWORDS.contains(word)) {
                throw new IllegalArgumentException("Only read-only SELECT queries are allowed: '"
                        + words.group().toUpperCase(Locale.ROOT)
                        + "' is not permitted (this console cannot modify data). "
                        + "If it is a column name, double-quote it.");
            }
        }
        if (first == null) {
            throw new IllegalArgumentException("Empty query. Enter a single SELECT statement.");
        }
        return sql.trim();
    }

    /**
     * Replace the CONTENT of string literals ({@code '…'}, {@code E'…'}, dollar-quoted), quoted
     * identifiers ({@code "…"}) and comments ({@code --}, nested {@code /* *&#47;}) with spaces so
     * keyword scanning and semicolon detection never trip over (or get fooled by) literal text.
     * The structure (quote characters, length) is otherwise preserved.
     */
    static String maskLiteralsAndComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            // The mask is LENGTH-PRESERVING (each consumed char becomes one space) so positions in
            // the masked text map 1:1 onto the original — the semicolon cut relies on that.
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int depth = 1;
                int from = i;
                i += 2;
                while (i < n && depth > 0) { // PostgreSQL block comments nest
                    if (sql.charAt(i) == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                        depth++;
                        i += 2;
                    } else if (sql.charAt(i) == '*' && i + 1 < n && sql.charAt(i + 1) == '/') {
                        depth--;
                        i += 2;
                    } else {
                        i++;
                    }
                }
                out.append(" ".repeat(i - from));
            } else if (c == '\'' || c == '"') {
                // E'…' escape strings honour backslash escapes; everything else doubles the quote.
                boolean backslashEscapes = c == '\'' && i > 0
                        && (sql.charAt(i - 1) == 'e' || sql.charAt(i - 1) == 'E')
                        && (i < 2 || !isWordChar(sql.charAt(i - 2)));
                out.append(c);
                i++;
                while (i < n) {
                    char d = sql.charAt(i);
                    if (backslashEscapes && d == '\\' && i + 1 < n) {
                        out.append("  ");
                        i += 2;
                    } else if (d == c) {
                        if (i + 1 < n && sql.charAt(i + 1) == c) { // '' / "" escape
                            out.append("  ");
                            i += 2;
                        } else {
                            out.append(c);
                            i++;
                            break;
                        }
                    } else {
                        out.append(' ');
                        i++;
                    }
                }
            } else if (c == '$') {
                // Dollar-quoted string: $tag$ … $tag$ (also bare $$ … $$).
                int j = i + 1;
                while (j < n && isWordChar(sql.charAt(j))) {
                    j++;
                }
                if (j < n && sql.charAt(j) == '$') {
                    String tag = sql.substring(i, j + 1);
                    int close = sql.indexOf(tag, j + 1);
                    int contentEnd = close < 0 ? n : close;
                    out.append(tag);
                    out.append(" ".repeat(Math.max(0, contentEnd - (i + tag.length()))));
                    if (close >= 0) {
                        out.append(tag);
                        i = close + tag.length();
                    } else {
                        i = n;
                    }
                } else {
                    out.append(c);
                    i++;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
