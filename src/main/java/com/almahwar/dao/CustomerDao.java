package com.almahwar.dao;

import com.almahwar.model.Customer;
import com.almahwar.model.CustomerType;
import com.almahwar.model.PartyFilter;
import com.almahwar.util.PhoneNumbers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Data access for {@code Customers}.
 * <p>
 * {@code balance} is not written by {@link #update(Customer)}; it changes only
 * through {@link #adjustBalance(Connection, int, BigDecimal)}, called by the
 * {@code AccountLedger} service class in the same transaction as the ledger entry.
 */
public class CustomerDao extends BaseDao {

    private static final String SELECT = """
            SELECT customer_id, customer_code, name, customer_type, phone, phone2, email, area, address,
                   credit_limit, opening_balance, balance, notes, is_active, created_at, updated_at
            FROM dbo.Customers
            """;

    public List<Customer> findAll() {
        return queryList(SELECT + " ORDER BY name", CustomerDao::map);
    }

    public List<Customer> findAllActive() {
        return queryList(SELECT + " WHERE is_active = 1 ORDER BY name", CustomerDao::map);
    }

    /**
     * Reads the customer and locks the row ({@code UPDLOCK}) until the transaction ends, so two credit sales or
     * two payments of the same customer are checked against the balance one after the other, never both against
     * the same old balance.
     */
    public Optional<Customer> lockForUpdate(Connection con, int customerId) {
        return queryOne(con, SELECT.replace("FROM dbo.Customers", "FROM dbo.Customers WITH (UPDLOCK, ROWLOCK)")
                + " WHERE customer_id = ?", CustomerDao::map, customerId);
    }

    public Optional<Customer> findById(int customerId) {
        return queryOne(SELECT + " WHERE customer_id = ?", CustomerDao::map, customerId);
    }

    public Optional<Customer> findByCode(String customerCode) {
        return queryOne(SELECT + " WHERE customer_code = ?", CustomerDao::map, customerCode);
    }

    /** The default walk-in customer used for cash sales. */
    public Optional<Customer> findCashCustomer() {
        return findByCode(Customer.CASH_CUSTOMER_CODE);
    }

    /** Searches name, code and phone numbers. */
    public List<Customer> search(String text) {
        if (text == null || text.isBlank()) {
            return findAll();
        }
        String like = likeContains(text);
        return queryList(SELECT + " WHERE name LIKE ? OR customer_code LIKE ? OR phone LIKE ? OR phone2 LIKE ? ORDER BY name",
                CustomerDao::map, like, like, like, like);
    }

    /** Customer list screen; every criterion of the filter is optional. */
    public List<Customer> search(PartyFilter filter) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (filter.text() != null) {
            String like = likeContains(filter.text());
            String digits = PhoneNumbers.digits(filter.text());
            sql.append(" AND (name LIKE ? OR customer_code LIKE ? OR phone LIKE ? OR phone2 LIKE ?");
            params.addAll(List.of(like, like, like, like));
            if (digits.length() >= 3) {   // "9988 7766" finds 99887766
                sql.append(" OR phone LIKE ? OR phone2 LIKE ?");
                params.addAll(List.of(likeContains(digits), likeContains(digits)));
            }
            sql.append(")");
        }
        switch (filter.status()) {
            case ACTIVE -> sql.append(" AND is_active = 1");
            case INACTIVE -> sql.append(" AND is_active = 0");
            case ALL -> { }
        }
        if (filter.withBalance()) {
            sql.append(" AND balance <> 0");
        }
        if (filter.overCreditOnly()) {
            sql.append(" AND balance > credit_limit");
        }
        sql.append(filter.withBalance() || filter.overCreditOnly() ? " ORDER BY balance DESC, name" : " ORDER BY name");
        return queryList(sql.toString(), CustomerDao::map, params.toArray());
    }

    /** Other customers (not {@code excludeId}) using this stored phone number as phone or alternate phone. */
    public List<Customer> findByPhone(String phone, Integer excludeId) {
        return queryList(SELECT + " WHERE (phone = ? OR phone2 = ?) AND customer_id <> ? ORDER BY name",
                CustomerDao::map, phone, phone, excludeId == null ? 0 : excludeId);
    }

    public boolean existsByCode(String customerCode, Integer excludeId) {
        return queryLong("SELECT COUNT(*) FROM dbo.Customers WHERE customer_code = ? AND customer_id <> ?",
                customerCode.trim(), excludeId == null ? 0 : excludeId) > 0;
    }

    /**
     * Next free automatic code {@code C-0001, C-0002 ...}. Inside a transaction the range is locked
     * until commit, so two users saving at once cannot get the same code.
     */
    public String nextCode(Connection con) {
        String sql = """
                SELECT COALESCE(MAX(TRY_CAST(SUBSTRING(customer_code, 3, 20) AS INT)), 0) + 1
                FROM dbo.Customers WITH (UPDLOCK, HOLDLOCK)
                WHERE customer_code LIKE N'C-[0-9]%'
                """;
        int next = (con == null ? queryOne(sql, rs -> rs.getInt(1)) : queryOne(con, sql, rs -> rs.getInt(1))).orElse(1);
        return String.format(java.util.Locale.ROOT, "C-%04d", next);
    }

    /** Inserts with balance 0; the opening balance is then posted to the ledger in the same transaction. */
    public int insert(Connection con, Customer c) {
        int id = insert(con, """
                INSERT INTO dbo.Customers (customer_code, name, customer_type, phone, phone2, email, area, address,
                    credit_limit, opening_balance, balance, notes, is_active)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                """,
                c.getCustomerCode(), c.getName(), c.getCustomerType().code(), c.getPhone(), c.getPhone2(),
                c.getEmail(), c.getArea(), c.getAddress(), c.getCreditLimit(), c.getOpeningBalance(),
                c.getNotes(), c.isActive());
        c.setCustomerId(id);
        c.setBalance(BigDecimal.ZERO);
        return id;
    }

    /** Customers who owe money, largest balance first. */
    public List<Customer> findWithBalance() {
        return queryList(SELECT + " WHERE balance > 0 ORDER BY balance DESC", CustomerDao::map);
    }

    /** Inserts the customer; the starting balance equals the opening balance. */
    public int insert(Customer c) {
        int id = insert("""
                INSERT INTO dbo.Customers (customer_code, name, customer_type, phone, phone2, email, area, address,
                    credit_limit, opening_balance, balance, notes, is_active)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                c.getCustomerCode(), c.getName(), c.getCustomerType().code(), c.getPhone(), c.getPhone2(),
                c.getEmail(), c.getArea(), c.getAddress(), c.getCreditLimit(), c.getOpeningBalance(),
                c.getOpeningBalance(), c.getNotes(), c.isActive());
        c.setCustomerId(id);
        c.setBalance(c.getOpeningBalance());
        return id;
    }

    /** Updates contact details; does not change balance or opening balance. */
    public void update(Customer c) {
        int rows = update("""
                UPDATE dbo.Customers
                SET customer_code = ?, name = ?, customer_type = ?, phone = ?, phone2 = ?, email = ?, area = ?,
                    address = ?, credit_limit = ?, notes = ?, is_active = ?, updated_at = SYSDATETIME()
                WHERE customer_id = ?
                """,
                c.getCustomerCode(), c.getName(), c.getCustomerType().code(), c.getPhone(), c.getPhone2(),
                c.getEmail(), c.getArea(), c.getAddress(), c.getCreditLimit(), c.getNotes(), c.isActive(),
                c.getCustomerId());
        requireOneRow(rows, "Customer", c.getCustomerId());
    }

    /**
     * Atomically adds {@code delta} to the balance in the caller's transaction
     * (positive for a credit sale, negative for a payment or return).
     *
     * @return the new balance
     */
    public BigDecimal adjustBalance(Connection con, int customerId, BigDecimal delta) {
        return queryOne(con, """
                UPDATE dbo.Customers
                SET balance = balance + ?, updated_at = SYSDATETIME()
                OUTPUT inserted.balance
                WHERE customer_id = ?
                """, rs -> rs.getBigDecimal(1), delta, customerId)
                .orElseThrow(() -> new DataAccessException("Customer not found: " + customerId));
    }

    public void setActive(int customerId, boolean active) {
        int rows = update("UPDATE dbo.Customers SET is_active = ?, updated_at = SYSDATETIME() WHERE customer_id = ?",
                active, customerId);
        requireOneRow(rows, "Customer", customerId);
    }

    /** Fails with a foreign-key error once the customer has invoices; deactivate instead. */
    public void delete(int customerId) {
        requireOneRow(update("DELETE FROM dbo.Customers WHERE customer_id = ?", customerId), "Customer", customerId);
    }

    private static Customer map(ResultSet rs) throws SQLException {
        Customer c = new Customer();
        c.setCustomerId(rs.getInt("customer_id"));
        c.setCustomerCode(rs.getString("customer_code"));
        c.setName(rs.getString("name"));
        c.setCustomerType(CustomerType.fromCode(rs.getString("customer_type")));
        c.setPhone(rs.getString("phone"));
        c.setPhone2(rs.getString("phone2"));
        c.setEmail(rs.getString("email"));
        c.setArea(rs.getString("area"));
        c.setAddress(rs.getString("address"));
        c.setCreditLimit(rs.getBigDecimal("credit_limit"));
        c.setOpeningBalance(rs.getBigDecimal("opening_balance"));
        c.setBalance(rs.getBigDecimal("balance"));
        c.setNotes(rs.getString("notes"));
        c.setActive(rs.getBoolean("is_active"));
        c.setCreatedAt(getDateTime(rs, "created_at"));
        c.setUpdatedAt(getDateTime(rs, "updated_at"));
        return c;
    }
}
