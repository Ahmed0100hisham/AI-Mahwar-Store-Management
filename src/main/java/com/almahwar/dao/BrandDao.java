package com.almahwar.dao;

import com.almahwar.model.Brand;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** Data access for {@code Brands}. */
public class BrandDao extends BaseDao {

    private static final String SELECT =
            "SELECT brand_id, name_ar, name_en, country, is_active, created_at, updated_at FROM dbo.Brands";

    public List<Brand> findAll() {
        return queryList(SELECT + " ORDER BY name_ar", BrandDao::map);
    }

    public List<Brand> findAllActive() {
        return queryList(SELECT + " WHERE is_active = 1 ORDER BY name_ar", BrandDao::map);
    }

    public Optional<Brand> findById(int brandId) {
        return queryOne(SELECT + " WHERE brand_id = ?", BrandDao::map, brandId);
    }

    public int insert(Brand brand) {
        int id = insert("INSERT INTO dbo.Brands (name_ar, name_en, country, is_active) VALUES (?, ?, ?, ?)",
                brand.getNameAr(), brand.getNameEn(), brand.getCountry(), brand.isActive());
        brand.setBrandId(id);
        return id;
    }

    public void update(Brand brand) {
        int rows = update("""
                UPDATE dbo.Brands
                SET name_ar = ?, name_en = ?, country = ?, is_active = ?, updated_at = SYSDATETIME()
                WHERE brand_id = ?
                """,
                brand.getNameAr(), brand.getNameEn(), brand.getCountry(), brand.isActive(), brand.getBrandId());
        requireOneRow(rows, "Brand", brand.getBrandId());
    }

    public void setActive(int brandId, boolean active) {
        int rows = update("UPDATE dbo.Brands SET is_active = ?, updated_at = SYSDATETIME() WHERE brand_id = ?",
                active, brandId);
        requireOneRow(rows, "Brand", brandId);
    }

    /** Fails with a foreign-key error while products use this brand. */
    public void delete(int brandId) {
        requireOneRow(update("DELETE FROM dbo.Brands WHERE brand_id = ?", brandId), "Brand", brandId);
    }

    private static Brand map(ResultSet rs) throws SQLException {
        Brand b = new Brand();
        b.setBrandId(rs.getInt("brand_id"));
        b.setNameAr(rs.getString("name_ar"));
        b.setNameEn(rs.getString("name_en"));
        b.setCountry(rs.getString("country"));
        b.setActive(rs.getBoolean("is_active"));
        b.setCreatedAt(getDateTime(rs, "created_at"));
        b.setUpdatedAt(getDateTime(rs, "updated_at"));
        return b;
    }
}
