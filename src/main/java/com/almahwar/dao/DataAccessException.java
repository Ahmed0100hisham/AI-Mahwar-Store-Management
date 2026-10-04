package com.almahwar.dao;

import java.sql.SQLException;

/**
 * Unchecked wrapper for {@link SQLException} thrown by the DAO layer.
 * <p>
 * Exposes helpers for the SQL Server errors the UI needs to explain to the
 * user (duplicate code/barcode, record still in use, invalid value).
 */
public class DataAccessException extends RuntimeException {

    // SQL Server error numbers
    private static final int UNIQUE_CONSTRAINT = 2627;
    private static final int UNIQUE_INDEX = 2601;
    private static final int CONSTRAINT_CONFLICT = 547;   // FOREIGN KEY or CHECK

    public DataAccessException(String message, Throwable cause) {
        super(message, cause);
    }

    public DataAccessException(String message) {
        super(message);
    }

    private SQLException sqlCause() {
        return getCause() instanceof SQLException e ? e : null;
    }

    private int errorCode() {
        SQLException e = sqlCause();
        return e == null ? 0 : e.getErrorCode();
    }

    private boolean messageContains(String text) {
        SQLException e = sqlCause();
        return e != null && e.getMessage() != null && e.getMessage().contains(text);
    }

    /** A UNIQUE constraint/index was violated (e.g. duplicate product code or barcode). */
    public boolean isDuplicateKey() {
        int code = errorCode();
        return code == UNIQUE_CONSTRAINT || code == UNIQUE_INDEX;
    }

    /** A FOREIGN KEY was violated, e.g. deleting a category that still has products. */
    public boolean isForeignKeyViolation() {
        return errorCode() == CONSTRAINT_CONFLICT
                && (messageContains("FOREIGN KEY") || messageContains("REFERENCE"));
    }

    /** A CHECK constraint was violated, e.g. a negative price. */
    public boolean isCheckViolation() {
        return errorCode() == CONSTRAINT_CONFLICT && messageContains("CHECK");
    }

    /** Arabic message suitable for showing to the user. */
    public String getUserMessage() {
        if (isDuplicateKey()) {
            return "القيمة المدخلة مسجلة مسبقًا (مثل الكود أو الباركود أو الاسم).";
        }
        if (isForeignKeyViolation()) {
            return "لا يمكن تنفيذ العملية لأن هذا السجل مرتبط ببيانات أخرى.";
        }
        if (isCheckViolation()) {
            return "إحدى القيم المدخلة غير صالحة.";
        }
        return "حدث خطأ أثناء الاتصال بقاعدة البيانات.";
    }
}
