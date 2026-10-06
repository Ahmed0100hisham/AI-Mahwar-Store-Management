package com.almahwar.service;

import com.almahwar.model.BackupInfo;
import com.almahwar.model.BackupResult;
import com.almahwar.model.BackupSettings;
import com.almahwar.model.BackupVerificationResult;
import com.almahwar.model.RestoreResult;

import java.util.List;

/**
 * Full database backup, verification and restore of the program's SQL Server database (admin only: the
 * {@code BACKUP_*} permissions, checked here and not only by the screen).
 * <p>
 * SQL Server writes and reads the backup files itself, in a folder on the server machine
 * ({@code backup.server-directory}, as SQL Server sees it — a trusted setting, never typed in the program).
 * Only one backup / restore runs at a time across all PCs. Failures throw {@link BackupException} with an Arabic
 * message. Kept free of JavaFX so a future REST API can expose the same operations.
 */
public interface BackupRestoreService {

    /** Field of the typed restore confirmation in a {@link ValidationException}. */
    String CONFIRMATION = "confirmation";

    /** Where backups go and whether they can be made now ({@code BACKUP_VIEW}). */
    BackupSettings settings();

    /** The backup history, newest first ({@code BACKUP_VIEW}). */
    List<BackupInfo> history();

    /**
     * Makes a full backup ({@code BACKUP_CREATE}) under a new, never-overwriting file name, records it, and — with
     * {@code BACKUP_VERIFY} — has SQL Server verify it right away.
     */
    BackupResult createBackup();

    /**
     * Has SQL Server verify a recorded backup ({@code BACKUP_VERIFY}): RESTORE VERIFYONLY with checksums, and the
     * file's header must show a full backup of this program's database. The result is saved in the history.
     * A failed verification is a result (not an exception); a missing / unusable backup is a {@link ValidationException}.
     */
    BackupVerificationResult verify(int backupId);

    /**
     * Replaces the database with a recorded backup ({@code BACKUP_RESTORE}). Requires the database name typed
     * exactly ({@code typedConfirmation}). Steps: verify the backup, check it belongs to this database and schema,
     * take and verify a safety backup ({@code PRE_RESTORE_…}; if that fails nothing is restored), restore from
     * {@code master} with other connections closed, then validate the restored database. The current session
     * always ends once the database was replaced: the user logs in again.
     */
    RestoreResult restore(int backupId, String typedConfirmation);

    /** The database name to type for a restore. */
    String databaseName();
}
