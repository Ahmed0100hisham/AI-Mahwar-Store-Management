package com.almahwar.api.health;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Read-only facts for the schema compatibility check. Never writes, repairs or migrates anything. */
@Repository
public class DatabaseStatusRepository {

    private final JdbcClient jdbc;

    public DatabaseStatusRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * {@code Schema_Info.schema_version}; empty when the table or its row does not exist. Two statements, as on the
     * desktop: SQL Server compiles a whole statement first, so a query naming a missing table fails even behind a CASE.
     */
    public Optional<String> schemaVersion() {
        Integer table = jdbc.sql("SELECT OBJECT_ID(N'dbo.Schema_Info', N'U')").query(Integer.class).optional().orElse(null);
        if (table == null) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT schema_version FROM dbo.Schema_Info WHERE id = 1").query(String.class).optional();
    }

    public List<String> missingTables(Collection<String> tables) {
        List<String> missing = new ArrayList<>();
        for (String table : tables) {
            Integer id = jdbc.sql("SELECT OBJECT_ID(?, N'U')").param("dbo." + table).query(Integer.class)
                    .optional().orElse(null);
            if (id == null) {
                missing.add(table);
            }
        }
        return missing;
    }
}
