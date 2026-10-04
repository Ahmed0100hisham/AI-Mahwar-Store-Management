package com.almahwar.dao;

import com.almahwar.model.Unit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** Data access for {@code Units}. */
public class UnitDao extends BaseDao {

    private static final String SELECT =
            "SELECT unit_id, name_ar, name_en, symbol, allows_decimal, is_active, created_at FROM dbo.Units";

    public List<Unit> findAll() {
        return queryList(SELECT + " ORDER BY unit_id", UnitDao::map);
    }

    public List<Unit> findAllActive() {
        return queryList(SELECT + " WHERE is_active = 1 ORDER BY unit_id", UnitDao::map);
    }

    public Optional<Unit> findById(int unitId) {
        return queryOne(SELECT + " WHERE unit_id = ?", UnitDao::map, unitId);
    }

    public int insert(Unit unit) {
        int id = insert("INSERT INTO dbo.Units (name_ar, name_en, symbol, allows_decimal, is_active) VALUES (?, ?, ?, ?, ?)",
                unit.getNameAr(), unit.getNameEn(), unit.getSymbol(), unit.isAllowsDecimal(), unit.isActive());
        unit.setUnitId(id);
        return id;
    }

    public void update(Unit unit) {
        int rows = update("""
                UPDATE dbo.Units SET name_ar = ?, name_en = ?, symbol = ?, allows_decimal = ?, is_active = ?
                WHERE unit_id = ?
                """,
                unit.getNameAr(), unit.getNameEn(), unit.getSymbol(), unit.isAllowsDecimal(), unit.isActive(),
                unit.getUnitId());
        requireOneRow(rows, "Unit", unit.getUnitId());
    }

    /** Fails with a foreign-key error while products use this unit. */
    public void delete(int unitId) {
        requireOneRow(update("DELETE FROM dbo.Units WHERE unit_id = ?", unitId), "Unit", unitId);
    }

    private static Unit map(ResultSet rs) throws SQLException {
        Unit u = new Unit();
        u.setUnitId(rs.getInt("unit_id"));
        u.setNameAr(rs.getString("name_ar"));
        u.setNameEn(rs.getString("name_en"));
        u.setSymbol(rs.getString("symbol"));
        u.setAllowsDecimal(rs.getBoolean("allows_decimal"));
        u.setActive(rs.getBoolean("is_active"));
        u.setCreatedAt(getDateTime(rs, "created_at"));
        return u;
    }
}
