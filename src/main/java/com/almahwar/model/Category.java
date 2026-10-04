package com.almahwar.model;

import java.time.LocalDateTime;
import java.util.Objects;

/** Table: Categories (supports one optional parent for sub-categories) */
public class Category {

    private Integer categoryId;
    private String nameAr;
    private String nameEn;
    private Integer parentCategoryId;
    private String description;
    private boolean active = true;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Read-only, joined from the parent category
    private String parentName;

    public Integer getCategoryId() { return categoryId; }
    public void setCategoryId(Integer categoryId) { this.categoryId = categoryId; }

    public String getNameAr() { return nameAr; }
    public void setNameAr(String nameAr) { this.nameAr = nameAr; }

    public String getNameEn() { return nameEn; }
    public void setNameEn(String nameEn) { this.nameEn = nameEn; }

    public Integer getParentCategoryId() { return parentCategoryId; }
    public void setParentCategoryId(Integer parentCategoryId) { this.parentCategoryId = parentCategoryId; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public String getParentName() { return parentName; }
    public void setParentName(String parentName) { this.parentName = parentName; }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof Category other && categoryId != null && categoryId.equals(other.categoryId));
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(categoryId);
    }

    @Override
    public String toString() {
        return nameAr;
    }
}
