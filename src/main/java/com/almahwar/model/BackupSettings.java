package com.almahwar.model;

/**
 * Backup configuration as the backup screen shows it (no credentials, ever).
 *
 * @param enabled          {@code backup.enabled}
 * @param databaseName     the program's database ({@code db.name}) — what is backed up and restored
 * @param serverDirectory  the backup folder as SQL Server sees it; {@code null} if it cannot be determined
 * @param fromServerDefault the folder is SQL Server's own default backup folder (no {@code backup.server-directory})
 * @param problem          why backups cannot be made now (Arabic), or {@code null}
 */
public record BackupSettings(boolean enabled, String databaseName, String serverDirectory, boolean fromServerDefault,
                             String problem) {

    public boolean ready() {
        return problem == null;
    }
}
