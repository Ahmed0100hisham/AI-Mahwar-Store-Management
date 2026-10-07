package com.almahwar.service;

import com.almahwar.config.DatabaseConnection;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The temporary database of the golden behaviour tests: created from the project's own schema script
 * ({@code database/01_create_database.sql}, schema 1.10.0) under a test-only name, and dumped as normalised text.
 * <p>
 * <b>Safety:</b> refuses to create, drop or seed any database whose name is not a recognised temporary test name —
 * in particular {@code AlMahwarDB}. The program connects to {@code db.name} (one database per JVM), so a golden run is
 * started with {@code -Ddb.name=AlMahwarGolden...}.
 * <p>
 * <b>Normalisation</b> (only inherently non-deterministic values): date / time columns become {@code <ts>} (NULL stays
 * NULL), {@code yyyy-MM-dd} dates inside text become {@code <date>}, GUIDs become {@code <uuid#n>} by first appearance
 * (equal GUIDs stay equal). Identity values, money, quantities, statuses, texts, audit actions and the audit machine
 * name are compared exactly — in a fresh database driven by the same sequential scenarios they are deterministic.
 */
final class GoldenDatabase {

    private static final Pattern TEMP_NAME = Pattern.compile("AlMahwar(Golden|Provider|Concurrency)[A-Za-z0-9_]*");
    private static final Pattern GO = Pattern.compile("(?im)^\\s*GO\\s*$");
    private static final Pattern DATE_IN_TEXT = Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b");

    private final Map<String, String> uuids = new LinkedHashMap<>();

    /** The database this JVM's DAOs use, after checking it is a temporary test database. */
    static String requireTemporaryDatabase() {
        String name = DatabaseConnection.databaseName();
        if (name == null || name.equalsIgnoreCase("AlMahwarDB") || !TEMP_NAME.matcher(name).matches()) {
            throw new IllegalStateException("REFUSED: golden tests only run on a temporary database "
                    + "(-Ddb.name=AlMahwarGolden...), not on '" + name + "'");
        }
        return name;
    }

    /** Drops a leftover database of the same temporary name, then creates it from the schema script. */
    static void create() throws SQLException, IOException {
        String name = requireTemporaryDatabase();
        drop();
        String script = Files.readString(Path.of("database", "01_create_database.sql"), StandardCharsets.UTF_8)
                .replace("AlMahwarDB", name);
        try (Connection con = DatabaseConnection.getConnection("master"); Statement st = con.createStatement()) {
            for (String batch : GO.split(script)) {
                if (!batch.isBlank()) {
                    st.execute(batch);
                }
            }
        }
    }

    static void drop() throws SQLException {
        String name = requireTemporaryDatabase();
        try (Connection con = DatabaseConnection.getConnection("master"); Statement st = con.createStatement()) {
            st.execute("IF DB_ID(N'" + name + "') IS NOT NULL BEGIN ALTER DATABASE [" + name
                    + "] SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE [" + name + "]; END");
        }
    }

    static void exec(String sql) throws SQLException {
        requireTemporaryDatabase();
        try (Connection con = DatabaseConnection.getConnection(); Statement st = con.createStatement()) {
            st.execute(sql);
        }
    }

    /** Every table of the database, every row, ordered, normalised. */
    String dump() throws SQLException {
        requireTemporaryDatabase();
        StringBuilder out = new StringBuilder();
        try (Connection con = DatabaseConnection.getConnection()) {
            List<String> tables = new ArrayList<>();
            try (Statement st = con.createStatement();
                 ResultSet rs = st.executeQuery("SELECT name FROM sys.tables WHERE schema_id = SCHEMA_ID('dbo') "
                         + "ORDER BY name")) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
            for (String table : tables) {
                try (Statement st = con.createStatement();
                     ResultSet rs = st.executeQuery("SELECT * FROM dbo.[" + table + "] ORDER BY 1")) {
                    ResultSetMetaData md = rs.getMetaData();
                    StringBuilder header = new StringBuilder("[").append(table).append("] ");
                    for (int c = 1; c <= md.getColumnCount(); c++) {
                        header.append(c > 1 ? "|" : "").append(md.getColumnName(c));
                    }
                    List<String> rows = new ArrayList<>();
                    while (rs.next()) {
                        StringBuilder row = new StringBuilder("  ");
                        for (int c = 1; c <= md.getColumnCount(); c++) {
                            row.append(c > 1 ? "|" : "").append(value(rs, c, md.getColumnType(c),
                                    md.getColumnTypeName(c)));
                        }
                        rows.add(row.toString());
                    }
                    out.append(header).append(" (").append(rows.size()).append(" rows)\n");
                    rows.forEach(r -> out.append(r).append('\n'));
                }
            }
        }
        return out.toString();
    }

    private String value(ResultSet rs, int c, int type, String typeName) throws SQLException {
        Object raw = rs.getObject(c);
        if (raw == null) {
            return "NULL";
        }
        switch (type) {
            case Types.DATE, Types.TIME, Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE, -151 /* datetime */,
                 -155 /* datetimeoffset */ -> {
                return "<ts>";
            }
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> {
                byte[] b = rs.getBytes(c);
                return "<bin " + b.length + " bytes, hash " + java.util.Arrays.hashCode(b) + ">";
            }
            case Types.DECIMAL, Types.NUMERIC -> {
                return rs.getBigDecimal(c).toPlainString();
            }
            default -> {
                if ("uniqueidentifier".equalsIgnoreCase(typeName)) {
                    return uuids.computeIfAbsent(raw.toString().toUpperCase(), k -> "<uuid#" + (uuids.size() + 1) + ">");
                }
                String text = raw.toString().replace("\r", "\\r").replace("\n", "\\n").replace("|", "\\|");
                return DATE_IN_TEXT.matcher(text).replaceAll("<date>");
            }
        }
    }
}
