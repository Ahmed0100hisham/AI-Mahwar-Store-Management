package com.almahwar.dao;

import com.almahwar.model.PartyFilter;
import com.almahwar.model.Supplier;
import com.almahwar.util.PhoneNumbers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Data access for {@code Suppliers}.
 * <p>
 * {@code balance} changes only through {@link #adjustBalance(Connection, int, BigDecimal)}, called by the
 * {@code AccountLedger} service class in the same transaction as the ledger entry.
 */
public class SupplierDao extends BaseDao {

    private static final String SELECT = """
            SELECT supplier_id, supplier_code, name, contact_person, phone, phone2, email, country, area, address,
                   opening_balance, balance, notes, is_active, created_at, updated_at
            FROM dbo.Suppliers
            """;

    public List<Supplier> findAll() {
        return queryList(SELECT + " ORDER BY name", SupplierDao::map);
    }

    public List<Supplier> findAllActive() {
        return queryList(SELECT + " WHERE is_active = 1 ORDER BY name", SupplierDao::map);
    }

    /**
     * Reads the supplier and locks the row ({@code UPDLOCK}) until the transaction ends, so two payments to the
     * same supplier are checked against the amount owed one after the other.
     */
    public Optional<Supplier> lockForUpdate(Connection con, int supplierId) {
        return queryOne(con, SELECT.replace("FROM dbo.Suppliers", "FROM dbo.Suppliers WITH (UPDLOCK, ROWLOCK)")
                + " WHERE supplier_id = ?", SupplierDao::map, supplierId);
    }

    public Optional<Supplier> findById(int supplierId) {
        return queryOne(SELECT + " WHERE supplier_id = ?", SupplierDao::map, supplierId);
    }

    public Optional<Supplier> findByCode(String supplierCode) {
        return queryOne(SELECT + " WHERE supplier_code = ?", SupplierDao::map, supplierCode);
    }

    /** Searches name, code, contact person and phone numbers. */
    public List<Supplier> search(String text) {
        if (text == null || text.isBlank()) {
            return findAll();
        }
        String like = likeContains(text);
        return queryList(SELECT + """
                 WHERE name LIKE ? OR supplier_code LIKE ? OR contact_person LIKE ? OR phone LIKE ? OR phone2 LIKE ?
                 ORDER BY name
                """, SupplierDao::map, like, like, like, like, like);
    }

    /** Supplier list screen; every criterion of the filter is optional ({@code overCreditOnly} is ignored). */
    public List<Supplier> search(PartyFilter filter) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (filter.text() != null) {
            String like = likeContains(filter.text());
            String digits = PhoneNumbers.digits(filter.text());
            sql.append(" AND (name LIKE ? OR supplier_code LIKE ? OR contact_person LIKE ? OR phone LIKE ? OR phone2 LIKE ?");
            params.addAll(List.of(like, like, like, like, like));
            if (digits.length() >= 3) {
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
        sql.append(filter.withBalance() ? " ORDER BY balance DESC, name" : " ORDER BY name");
        return queryList(sql.toString(), SupplierDao::map, params.toArray());
    }

    /** Other suppliers (not {@code excludeId}) using this stored phone number. */
    public List<Supplier> findByPhone(String phone, Integer excludeId) {
        return queryList(SELECT + " WHERE (phone = ? OR phone2 = ?) AND supplier_id <> ? ORDER BY name",
                SupplierDao::map, phone, phone, excludeId == null ? 0 : excludeId);
    }

    public boolean existsByCode(String supplierCode, Integer excludeId) {
        return queryLong("SELECT COUNT(*) FROM dbo.Suppliers WHERE supplier_code = ? AND supplier_id <> ?",
                supplierCode.trim(), excludeId == null ? 0 : excludeId) > 0;
    }

    /** Next free automatic code {@code S-0001 ...}; locked until commit inside a transaction. */
    public String nextCode(Connection con) {
        String sql = """
                SELECT COALESCE(MAX(TRY_CAST(SUBSTRING(supplier_code, 3, 20) AS INT)), 0) + 1
                FROM dbo.Suppliers WITH (UPDLOCK, HOLDLOCK)
                WHERE supplier_code LIKE N'S-[0-9]%'
                """;
        int next = (con == null ? queryOne(sql, rs -> rs.getInt(1)) : queryOne(con, sql, rs -> rs.getInt(1))).orElse(1);
        return String.format(java.util.Locale.ROOT, "S-%04d", next);
    }

    /** Inserts with balance 0; the opening balance is then posted to the ledger in the same transaction. */
    public int insert(Connection con, Supplier s) {
        int id = insert(con, """
                INSERT INTO dbo.Suppliers (supplier_code, name, contact_person, phone, phone2, email, country, area,
                    address, opening_balance, balance, notes, is_active)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                """,
                s.getSupplierCode(), s.getName(), s.getContactPerson(), s.getPhone(), s.getPhone2(), s.getEmail(),
                s.getCountry(), s.getArea(), s.getAddress(), s.getOpeningBalance(), s.getNotes(), s.isActive());
        s.setSupplierId(id);
        s.setBalance(BigDecimal.ZERO);
        return id;
    }

    /** Inserts the supplier; the starting balance equals the opening balance. */
    public int insert(Supplier s) {
        int id = insert("""
                INSERT INTO dbo.Suppliers (supplier_code, name, contact_person, phone, phone2, email, country, address,
                    opening_balance, balance, notes, is_active)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                s.getSupplierCode(), s.getName(), s.getContactPerson(), s.getPhone(), s.getPhone2(), s.getEmail(),
                s.getCountry(), s.getAddress(), s.getOpeningBalance(), s.getOpeningBalance(), s.getNotes(),
                s.isActive());
        s.setSupplierId(id);
        s.setBalance(s.getOpeningBalance());
        return id;
    }

    /** Updates contact details; does not change balance or opening balance. */
    public void update(Supplier s) {
        int rows = update("""
                UPDATE dbo.Suppliers
                SET supplier_code = ?, name = ?, contact_person = ?, phone = ?, phone2 = ?, email = ?, country = ?,
                    area = ?, address = ?, notes = ?, is_active = ?, updated_at = SYSDATETIME()
                WHERE supplier_id = ?
                """,
                s.getSupplierCode(), s.getName(), s.getContactPerson(), s.getPhone(), s.getPhone2(), s.getEmail(),
                s.getCountry(), s.getArea(), s.getAddress(), s.getNotes(), s.isActive(), s.getSupplierId());
        requireOneRow(rows, "Supplier", s.getSupplierId());
    }

    /**
     * Atomically adds {@code delta} to the balance in the caller's transaction
     * (positive for a credit purchase, negative for a payment or return).
     *
     * @return the new balance
     */
    public BigDecimal adjustBalance(Connection con, int supplierId, BigDecimal delta) {
        return queryOne(con, """
                UPDATE dbo.Suppliers
                SET balance = balance + ?, updated_at = SYSDATETIME()
                OUTPUT inserted.balance
                WHERE supplier_id = ?
                """, rs -> rs.getBigDecimal(1), delta, supplierId)
                .orElseThrow(() -> new DataAccessException("Supplier not found: " + supplierId));
    }

    public void setActive(int supplierId, boolean active) {
        int rows = update("UPDATE dbo.Suppliers SET is_active = ?, updated_at = SYSDATETIME() WHERE supplier_id = ?",
                active, supplierId);
        requireOneRow(rows, "Supplier", supplierId);
    }

    /** Fails with a foreign-key error once the supplier has purchases; deactivate instead. */
    public void delete(int supplierId) {
        requireOneRow(update("DELETE FROM dbo.Suppliers WHERE supplier_id = ?", supplierId), "Supplier", supplierId);
    }

    private static Supplier map(ResultSet rs) throws SQLException {
        Supplier s = new Supplier();
        s.setSupplierId(rs.getInt("supplier_id"));
        s.setSupplierCode(rs.getString("supplier_code"));
        s.setName(rs.getString("name"));
        s.setContactPerson(rs.getString("contact_person"));
        s.setPhone(rs.getString("phone"));
        s.setPhone2(rs.getString("phone2"));
        s.setEmail(rs.getString("email"));
        s.setCountry(rs.getString("country"));
        s.setArea(rs.getString("area"));
        s.setAddress(rs.getString("address"));
        s.setOpeningBalance(rs.getBigDecimal("opening_balance"));
        s.setBalance(rs.getBigDecimal("balance"));
        s.setNotes(rs.getString("notes"));
        s.setActive(rs.getBoolean("is_active"));
        s.setCreatedAt(getDateTime(rs, "created_at"));
        s.setUpdatedAt(getDateTime(rs, "updated_at"));
        return s;
    }
}
