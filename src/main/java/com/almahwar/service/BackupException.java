package com.almahwar.service;

/**
 * A backup / verify / restore operation failed on SQL Server (or could not start). The message is Arabic, short
 * and free of technical details and secrets; the technical cause is in the application log.
 */
public class BackupException extends RuntimeException {

    public BackupException(String message) {
        super(message);
    }

    public BackupException(String message, Throwable cause) {
        super(message, cause);
    }
}
