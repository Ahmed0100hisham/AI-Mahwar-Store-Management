package com.almahwar.model;

/**
 * Outcome of "create backup": the history row as saved, and whether the automatic verification right after it
 * succeeded ({@code verification} is {@code null} if it could not run).
 */
public record BackupResult(BackupInfo backup, BackupVerificationResult verification) {

    public boolean verified() {
        return verification != null && verification.valid();
    }
}
