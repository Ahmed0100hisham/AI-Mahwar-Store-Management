package com.almahwar.model;

import java.util.List;

/**
 * Outcome of a successful restore. The session that started it has ended: the user logs in again against the
 * restored data.
 *
 * @param restoredFile     the backup that is now the database
 * @param safetyBackupFile the automatic backup taken just before (to undo the restore if needed)
 * @param schemaVersion    {@code Schema_Info} of the restored database
 * @param checks           the post-restore health checks that passed (Arabic)
 * @param warnings         read-only consistency findings worth a look (Arabic); nothing is repaired automatically
 */
public record RestoreResult(String restoredFile, String safetyBackupFile, String schemaVersion, List<String> checks,
                            List<String> warnings) {
}
