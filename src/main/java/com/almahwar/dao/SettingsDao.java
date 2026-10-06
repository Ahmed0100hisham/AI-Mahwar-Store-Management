package com.almahwar.dao;

import com.almahwar.model.Settings.LogoInfo;

import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Settings (System_Settings key / value rows), the company logo (Company_Logo, one row) and the schema version
 * (Schema_Info). Writes take the caller's connection so a whole save is one transaction.
 */
public class SettingsDao extends BaseDao {

    /** Every setting, key → value ({@code null} values included). */
    public Map<String, String> loadAll() {
        Map<String, String> map = new HashMap<>();
        queryList("SELECT setting_key, setting_value FROM dbo.System_Settings",
                rs -> map.put(rs.getString(1), rs.getString(2)));
        return map;
    }

    /** Same, read inside a transaction with the rows locked (the values compared for the audit stay current). */
    public Map<String, String> lockAll(Connection con) {
        Map<String, String> map = new HashMap<>();
        queryList(con, "SELECT setting_key, setting_value FROM dbo.System_Settings WITH (UPDLOCK, HOLDLOCK)",
                rs -> map.put(rs.getString(1), rs.getString(2)));
        return map;
    }

    /** Sets one value (inserting the key if a newer program added it after the database was created). */
    public void put(Connection con, String key, String value, int userId) {
        int updated = update(con, "UPDATE dbo.System_Settings SET setting_value = ?, updated_at = SYSDATETIME(), "
                + "updated_by = ? WHERE setting_key = ?", value, userId, key);
        if (updated == 0) {
            update(con, "INSERT INTO dbo.System_Settings (setting_key, setting_value, updated_by) VALUES (?, ?, ?)",
                    key, value, userId);
        }
    }

    // ---------- logo ----------

    public Optional<byte[]> logo() {
        return queryOne("SELECT content FROM dbo.Company_Logo WHERE logo_id = 1", rs -> rs.getBytes(1));
    }

    public Optional<LogoInfo> logoInfo() {
        return queryOne("""
                SELECT l.file_name, l.content_type, l.width, l.height, l.size_bytes, l.updated_at, u.full_name
                FROM dbo.Company_Logo l LEFT JOIN dbo.Users u ON u.user_id = l.updated_by
                WHERE l.logo_id = 1
                """, rs -> new LogoInfo(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getInt(5),
                getDateTime(rs, "updated_at"), rs.getString(7)));
    }

    public void saveLogo(Connection con, byte[] content, String contentType, String fileName, int width, int height,
                         String sha256, int userId) {
        int updated = update(con, """
                UPDATE dbo.Company_Logo SET content = ?, content_type = ?, file_name = ?, width = ?, height = ?,
                       size_bytes = ?, sha256 = ?, updated_at = SYSDATETIME(), updated_by = ?
                WHERE logo_id = 1
                """, content, contentType, fileName, width, height, content.length, sha256, userId);
        if (updated == 0) {
            update(con, """
                    INSERT INTO dbo.Company_Logo (logo_id, content, content_type, file_name, width, height, size_bytes,
                                                  sha256, updated_by)
                    VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, content, contentType, fileName, width, height, content.length, sha256, userId);
        }
    }

    /** @return false if there was no logo */
    public boolean deleteLogo(Connection con) {
        return update(con, "DELETE FROM dbo.Company_Logo WHERE logo_id = 1") == 1;
    }

    // ---------- version & server ----------

    /** The database's schema version, or empty if the table does not exist yet (an older database). */
    public Optional<String> schemaVersion() {
        return queryOne("SELECT CASE WHEN OBJECT_ID(N'dbo.Schema_Info', N'U') IS NULL THEN NULL "
                + "ELSE (SELECT schema_version FROM dbo.Schema_Info WHERE id = 1) END", rs -> rs.getString(1));
    }

    /** SQL Server's product version and edition, e.g. "16.0.4135.4 (Developer Edition (64-bit))". */
    public String serverVersion() {
        return queryOne("SELECT CAST(SERVERPROPERTY('ProductVersion') AS nvarchar(50)) + N' (' "
                + "+ CAST(SERVERPROPERTY('Edition') AS nvarchar(100)) + N')'", rs -> rs.getString(1)).orElse(null);
    }
}
