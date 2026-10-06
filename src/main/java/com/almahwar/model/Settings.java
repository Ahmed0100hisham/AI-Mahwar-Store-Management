package com.almahwar.model;

import java.time.LocalDateTime;

/** Settings data returned by the settings service (no secrets: never credentials or connection strings). */
public final class Settings {

    private Settings() {
    }

    /** The stored company logo (the image itself is read separately). */
    public record LogoInfo(String fileName, String contentType, int width, int height, int sizeBytes,
                           LocalDateTime updatedAt, String updatedBy) {
    }

    /**
     * Everything the settings screen edits.
     *
     * @param logo {@code null} when no logo is stored
     */
    public record Snapshot(CompanySettings company, SystemSettings system, LogoInfo logo) {
    }

    /**
     * About the program.
     *
     * @param databaseDescription e.g. {@code "localhost / AlMahwarDB"} (never credentials)
     * @param schemaVersion       the database's schema version, or {@code null} if unknown / unreachable
     * @param schemaCompatible    the database schema is at least the version this program needs
     */
    public record About(String appName, String appNameEn, String appVersion, String javaVersion,
                        boolean databaseConnected, String databaseDescription, String sqlServerVersion,
                        String schemaVersion, String requiredSchemaVersion, boolean schemaCompatible) {
    }
}
