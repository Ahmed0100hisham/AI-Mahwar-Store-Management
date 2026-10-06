package com.almahwar.dao;

import com.almahwar.model.Category;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** Data access for {@code Categories}. */
public class CategoryDao extends BaseDao {

    private static final String SELECT = """
            SELECT c.category_id, c.name_ar, c.name_en, c.parent_category_id, c.description,
                   c.is_active, c.created_at, c.updated_at,
                   p.name_ar AS parent_name
            FROM dbo.Categories c
            LEFT JOIN dbo.Categories p ON p.category_id = c.parent_category_id
            """;

    public List<Category> findAll() {
        return queryList(SELECT + " ORDER BY c.name_ar", CategoryDao::map);
    }

    public List<Category> findAllActive() {
        return queryList(SELECT + " WHERE c.is_active = 1 ORDER BY c.name_ar", CategoryDao::map);
    }

    /** Arabic/English name contains {@code text}; {@code null} or blank text matches all. */
    public List<Category> search(String text, boolean activeOnly) {
        String like = text == null || text.isBlank() ? "%" : likeContains(text);
        return queryList(SELECT + " WHERE (c.name_ar LIKE ? OR c.name_en LIKE ?)"
                + (activeOnly ? " AND c.is_active = 1" : "") + " ORDER BY c.name_ar", CategoryDao::map, like, like);
    }

    public boolean existsByName(String nameAr, Integer excludeId) {
        return queryLong("SELECT COUNT(*) FROM dbo.Categories WHERE name_ar = ? AND category_id <> ?",
                nameAr.trim(), excludeId == null ? 0 : excludeId) > 0;
    }

    public Optional<Category> findById(int categoryId) {
        return queryOne(SELECT + " WHERE c.category_id = ?", CategoryDao::map, categoryId);
    }

    public int insert(Category category) {
        int id = insert("""
                INSERT INTO dbo.Categories (name_ar, name_en, parent_category_id, description, is_active)
                VALUES (?, ?, ?, ?, ?)
                """,
                category.getNameAr(), category.getNameEn(), category.getParentCategoryId(),
                category.getDescription(), category.isActive());
        category.setCategoryId(id);
        return id;
    }

    public void update(Category category) {
        int rows = update("""
                UPDATE dbo.Categories
                SET name_ar = ?, name_en = ?, parent_category_id = ?, description = ?, is_active = ?,
                    updated_at = SYSDATETIME()
                WHERE category_id = ?
                """,
                category.getNameAr(), category.getNameEn(), category.getParentCategoryId(),
                category.getDescription(), category.isActive(), category.getCategoryId());
        requireOneRow(rows, "Category", category.getCategoryId());
    }

    public void setActive(int categoryId, boolean active) {
        int rows = update("UPDATE dbo.Categories SET is_active = ?, updated_at = SYSDATETIME() WHERE category_id = ?",
                active, categoryId);
        requireOneRow(rows, "Category", categoryId);
    }

    /** Fails with a foreign-key error while products or sub-categories use it. */
    public void delete(int categoryId) {
        requireOneRow(update("DELETE FROM dbo.Categories WHERE category_id = ?", categoryId), "Category", categoryId);
    }

    private static Category map(ResultSet rs) throws SQLException {
        Category c = new Category();
        c.setCategoryId(rs.getInt("category_id"));
        c.setNameAr(rs.getString("name_ar"));
        c.setNameEn(rs.getString("name_en"));
        c.setParentCategoryId(getInteger(rs, "parent_category_id"));
        c.setDescription(rs.getString("description"));
        c.setActive(rs.getBoolean("is_active"));
        c.setCreatedAt(getDateTime(rs, "created_at"));
        c.setUpdatedAt(getDateTime(rs, "updated_at"));
        c.setParentName(rs.getString("parent_name"));
        return c;
    }
}
