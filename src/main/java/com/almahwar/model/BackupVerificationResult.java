package com.almahwar.model;

import java.time.LocalDateTime;

/**
 * What SQL Server said about a backup file: readable and complete (RESTORE VERIFYONLY with CHECKSUM), and — read
 * from the file's header — a full backup of the expected database. {@code message} is a short Arabic summary
 * without technical details.
 *
 * @param databaseInFile   the database the file belongs to, {@code null} if the header could not be read
 * @param backupFinishedAt when SQL Server finished writing the file (server time), if known
 */
public record BackupVerificationResult(int backupId, String fileName, boolean valid, String databaseInFile,
                                       LocalDateTime backupFinishedAt, Long sizeBytes, String message) {
}
