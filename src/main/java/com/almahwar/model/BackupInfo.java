package com.almahwar.model;

import java.time.LocalDateTime;

/**
 * One row of the backup history: a full database backup made by the program. The file lives on the SQL Server
 * machine ({@code serverDirectory} is a path as SQL Server sees it, not necessarily visible from this PC).
 * The outcome of making the file ({@link Status}) is kept apart from whether SQL Server could verify it
 * ({@link Verification}). No credentials are ever part of a backup row.
 */
public record BackupInfo(int backupId, String fileName, String serverDirectory, String databaseName, Kind kind,
                         Status status, Verification verification, String schemaVersion, Long sizeBytes,
                         LocalDateTime createdAt, String createdByName, LocalDateTime completedAt,
                         LocalDateTime verifiedAt, String verifiedByName, String note) {

    /** Why the backup was made. */
    public enum Kind {
        MANUAL("يدوية"),
        /** Taken automatically just before a restore replaced the database. */
        PRE_RESTORE("وقائية قبل الاستعادة");

        private final String labelAr;

        Kind(String labelAr) {
            this.labelAr = labelAr;
        }

        public String getLabelAr() {
            return labelAr;
        }
    }

    /** The backup operation itself. */
    public enum Status {
        /** Reserved and being written by SQL Server (or the program stopped while it was). */
        CREATING("قيد الإنشاء"),
        /** SQL Server reported success. */
        COMPLETED("مكتملة"),
        FAILED("فشلت");

        private final String labelAr;

        Status(String labelAr) {
            this.labelAr = labelAr;
        }

        public String getLabelAr() {
            return labelAr;
        }
    }

    /** The last verification of the file by SQL Server (RESTORE VERIFYONLY + header checks). */
    public enum Verification {
        NOT_VERIFIED("لم يتم التحقق"),
        VERIFIED("تم التحقق"),
        VERIFY_FAILED("فشل التحقق");

        private final String labelAr;

        Verification(String labelAr) {
            this.labelAr = labelAr;
        }

        public String getLabelAr() {
            return labelAr;
        }
    }

    /** Only a backup SQL Server finished writing can be verified or restored. */
    public boolean usable() {
        return status == Status.COMPLETED;
    }
}
