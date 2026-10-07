package com.almahwar.dao;

import java.sql.Connection;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Writes to {@code Audit_Log}.
 * <p>
 * The {@code machine_name} column records where an action came from. The desktop default ({@link #AuditLogDao()}) is
 * the name of this computer, resolved once per process exactly as in 1.0.0; another host of the business core (e.g. a
 * server) passes its own origin, asked for on every entry.
 */
public class AuditLogDao extends BaseDao {

    public static final String LOGIN = "LOGIN";
    public static final String LOGIN_FAILED = "LOGIN_FAILED";
    public static final String LOGOUT = "LOGOUT";
    public static final String INSERT = "INSERT";
    public static final String UPDATE = "UPDATE";
    public static final String DELETE = "DELETE";

    public static final String ACTIVATE = "ACTIVATE";
    public static final String DEACTIVATE = "DEACTIVATE";
    public static final String STOCK_ADJUSTMENT = "STOCK_ADJUSTMENT";
    public static final String CREATE_PURCHASE = "CREATE_PURCHASE";
    public static final String POST_PURCHASE = "POST_PURCHASE";
    public static final String CANCEL_PURCHASE = "CANCEL_PURCHASE";
    public static final String CREATE_SALE = "CREATE_SALE";
    public static final String POST_SALE = "POST_SALE";
    public static final String CANCEL_SALE = "CANCEL_SALE";
    /** A sale line sold at a price other than the product's list price. */
    public static final String PRICE_OVERRIDE = "PRICE_OVERRIDE";
    /** A credit sale accepted beyond the customer's credit limit. */
    public static final String CREDIT_OVERRIDE = "CREDIT_OVERRIDE";
    public static final String CUSTOMER_PAYMENT_CREATED = "CUSTOMER_PAYMENT_CREATED";
    public static final String SUPPLIER_PAYMENT_CREATED = "SUPPLIER_PAYMENT_CREATED";
    public static final String EXPENSE_CREATED = "EXPENSE_CREATED";
    public static final String CASH_DEPOSIT = "CASH_DEPOSIT";
    public static final String CASH_WITHDRAWAL = "CASH_WITHDRAWAL";
    public static final String SALE_RETURN_CREATED = "SALE_RETURN_CREATED";
    public static final String PURCHASE_RETURN_CREATED = "PURCHASE_RETURN_CREATED";
    public static final String QUOTATION_CREATED = "QUOTATION_CREATED";
    public static final String QUOTATION_UPDATED = "QUOTATION_UPDATED";
    public static final String QUOTATION_DELETED = "QUOTATION_DELETED";
    public static final String QUOTATION_SENT = "QUOTATION_SENT";
    public static final String QUOTATION_REOPENED = "QUOTATION_REOPENED";
    public static final String QUOTATION_ACCEPTED = "QUOTATION_ACCEPTED";
    public static final String QUOTATION_REJECTED = "QUOTATION_REJECTED";
    public static final String QUOTATION_EXPIRED = "QUOTATION_EXPIRED";
    public static final String QUOTATION_CONVERSION_STARTED = "QUOTATION_CONVERSION_STARTED";
    public static final String QUOTATION_CONVERTED = "QUOTATION_CONVERTED";
    public static final String QUOTATION_PRICE_OVERRIDE = "QUOTATION_PRICE_OVERRIDE";
    public static final String QUOTATION_DISCOUNT = "QUOTATION_DISCOUNT";
    public static final String COMPANY_SETTINGS_UPDATED = "COMPANY_SETTINGS_UPDATED";
    public static final String SYSTEM_SETTINGS_UPDATED = "SYSTEM_SETTINGS_UPDATED";
    public static final String LOGO_CHANGED = "LOGO_CHANGED";
    public static final String USER_CREATED = "USER_CREATED";
    public static final String USER_UPDATED = "USER_UPDATED";
    public static final String USER_ENABLED = "USER_ENABLED";
    public static final String USER_DISABLED = "USER_DISABLED";
    public static final String USER_ROLE_CHANGED = "USER_ROLE_CHANGED";
    public static final String PASSWORD_CHANGED = "PASSWORD_CHANGED";
    public static final String PASSWORD_RESET = "PASSWORD_RESET";
    public static final String ACCOUNT_LOCKED = "ACCOUNT_LOCKED";
    public static final String ACCOUNT_UNLOCKED = "ACCOUNT_UNLOCKED";
    public static final String BACKUP_STARTED = "BACKUP_STARTED";
    public static final String BACKUP_COMPLETED = "BACKUP_COMPLETED";
    public static final String BACKUP_FAILED = "BACKUP_FAILED";
    public static final String BACKUP_VERIFIED = "BACKUP_VERIFIED";
    public static final String BACKUP_VERIFY_FAILED = "BACKUP_VERIFY_FAILED";
    public static final String RESTORE_REQUESTED = "RESTORE_REQUESTED";
    public static final String PRE_RESTORE_BACKUP_CREATED = "PRE_RESTORE_BACKUP_CREATED";
    public static final String RESTORE_COMPLETED = "RESTORE_COMPLETED";
    public static final String RESTORE_FAILED = "RESTORE_FAILED";

    private static final String MACHINE_NAME = resolveMachineName();

    /** Origin of the entries written through this DAO ({@code machine_name}). */
    private final Supplier<String> origin;

    /** The desktop: entries carry this computer's name. */
    public AuditLogDao() {
        this(() -> MACHINE_NAME);
    }

    /** @param origin supplies {@code machine_name} for each entry (may return {@code null}) */
    public AuditLogDao(Supplier<String> origin) {
        this.origin = Objects.requireNonNull(origin, "origin");
    }

    private static final String SQL = """
            INSERT INTO dbo.Audit_Log (user_id, action, table_name, record_id, description, machine_name)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    private static final String SQL_WITH_VALUES = """
            INSERT INTO dbo.Audit_Log (user_id, action, table_name, record_id, old_values, new_values, description,
                                       machine_name)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    /**
     * @param userId      acting user, or {@code null} (e.g. failed login with an unknown username)
     * @param tableName   affected table, or {@code null} for login/logout
     * @param recordId    affected row id, or {@code null}
     */
    public void log(Integer userId, String action, String tableName, String recordId, String description) {
        update(SQL, userId, action, tableName, recordId, description, origin.get());
    }

    /** Same, inside the caller's transaction: the entry is kept only if the change itself commits. */
    public void log(Connection con, Integer userId, String action, String tableName, String recordId,
                    String description) {
        update(con, SQL, userId, action, tableName, recordId, description, origin.get());
    }

    /** Same, with the values before and after the change (JSON), e.g. an overridden price. */
    public void log(Connection con, Integer userId, String action, String tableName, String recordId,
                    String oldValues, String newValues, String description) {
        update(con, SQL_WITH_VALUES, userId, action, tableName, recordId, oldValues, newValues, description,
                origin.get());
    }

    private static String resolveMachineName() {
        String name = System.getenv("COMPUTERNAME");   // Windows
        if (name == null || name.isBlank()) {
            name = System.getenv("HOSTNAME");
        }
        return name;
    }
}
