package com.almahwar.service;

import com.almahwar.model.Brand;
import com.almahwar.model.Category;
import com.almahwar.model.Unit;

import java.util.List;

/**
 * Lookup data for products: categories (أقسام), brands (ماركات) and units (وحدات).
 * All lists come from the database; nothing is hard-coded. Records are deactivated, never deleted,
 * so old products and invoices keep their references.
 */
public interface CatalogService {

    String NAME_AR = "nameAr";
    String NAME_EN = "nameEn";
    String DESCRIPTION = "description";
    String COUNTRY = "country";
    String SYMBOL = "symbol";

    List<Category> categories(String text, boolean activeOnly);

    Category saveCategory(Category category);

    void setCategoryActive(int categoryId, boolean active);

    List<Brand> brands(String text, boolean activeOnly);

    Brand saveBrand(Brand brand);

    void setBrandActive(int brandId, boolean active);

    List<Unit> units(String text, boolean activeOnly);

    Unit saveUnit(Unit unit);

    void setUnitActive(int unitId, boolean active);
}
