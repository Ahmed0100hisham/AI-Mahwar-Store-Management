package com.almahwar.service;

import com.almahwar.dao.AuditLogDao;
import com.almahwar.dao.BrandDao;
import com.almahwar.dao.CategoryDao;
import com.almahwar.dao.DataAccessException;
import com.almahwar.dao.UnitDao;
import com.almahwar.model.Brand;
import com.almahwar.model.Category;
import com.almahwar.model.Permission;
import com.almahwar.model.Unit;

import java.util.List;
import java.util.function.Supplier;

import static com.almahwar.service.Validation.trimToNull;

/** Categories, brands and units on SQL Server. Reading is open to anyone who works with products. */
public class CatalogServiceImpl implements CatalogService {

    private static final Permission[] READ = {
            Permission.PRODUCTS_VIEW, Permission.PRODUCTS, Permission.INVENTORY, Permission.INVENTORY_ADJUST};

    private final CategoryDao categoryDao;
    private final BrandDao brandDao;
    private final UnitDao unitDao;
    private final AuditLogDao auditLogDao;
    private final SecurityContext security;

    public CatalogServiceImpl(CategoryDao categoryDao, BrandDao brandDao, UnitDao unitDao,
                              AuditLogDao auditLogDao, SecurityContext security) {
        this.categoryDao = categoryDao;
        this.brandDao = brandDao;
        this.unitDao = unitDao;
        this.auditLogDao = auditLogDao;
        this.security = security;
    }

    // ---------- Categories ----------

    @Override
    public List<Category> categories(String text, boolean activeOnly) {
        security.requireAnyPermission(READ);
        return categoryDao.search(text, activeOnly);
    }

    @Override
    public Category saveCategory(Category c) {
        security.requirePermission(Permission.PRODUCTS);
        c.setNameAr(trimToNull(c.getNameAr()));
        c.setNameEn(trimToNull(c.getNameEn()));
        c.setDescription(trimToNull(c.getDescription()));

        Validation v = new Validation();
        v.required(NAME_AR, c.getNameAr(), "اسم القسم بالعربي مطلوب.");
        v.maxLength(NAME_AR, c.getNameAr(), 100, "اسم القسم");
        v.maxLength(NAME_EN, c.getNameEn(), 100, "الاسم بالإنجليزي");
        v.maxLength(DESCRIPTION, c.getDescription(), 250, "الوصف");
        if (!v.has(NAME_AR) && categoryDao.existsByName(c.getNameAr(), c.getCategoryId())) {
            v.error(NAME_AR, "يوجد قسم آخر بنفس الاسم \"" + c.getNameAr() + "\".");
        }
        v.throwIfAny();

        boolean isNew = c.getCategoryId() == null;
        unique(() -> {
            if (isNew) {
                categoryDao.insert(c);
            } else {
                categoryDao.update(c);
            }
            return null;
        });
        audit(isNew, "Categories", c.getCategoryId(), "قسم " + c.getNameAr());
        return categoryDao.findById(c.getCategoryId()).orElseThrow();
    }

    @Override
    public void setCategoryActive(int categoryId, boolean active) {
        security.requirePermission(Permission.PRODUCTS);
        categoryDao.setActive(categoryId, active);
        auditActive(active, "Categories", categoryId);
    }

    // ---------- Brands ----------

    @Override
    public List<Brand> brands(String text, boolean activeOnly) {
        security.requireAnyPermission(READ);
        return brandDao.search(text, activeOnly);
    }

    @Override
    public Brand saveBrand(Brand b) {
        security.requirePermission(Permission.PRODUCTS);
        b.setNameAr(trimToNull(b.getNameAr()));
        b.setNameEn(trimToNull(b.getNameEn()));
        b.setCountry(trimToNull(b.getCountry()));

        Validation v = new Validation();
        v.required(NAME_AR, b.getNameAr(), "اسم الماركة مطلوب.");
        v.maxLength(NAME_AR, b.getNameAr(), 100, "اسم الماركة");
        v.maxLength(NAME_EN, b.getNameEn(), 100, "الاسم بالإنجليزي");
        v.maxLength(COUNTRY, b.getCountry(), 50, "بلد المنشأ");
        if (!v.has(NAME_AR) && brandDao.existsByName(b.getNameAr(), b.getBrandId())) {
            v.error(NAME_AR, "توجد ماركة أخرى بنفس الاسم \"" + b.getNameAr() + "\".");
        }
        v.throwIfAny();

        boolean isNew = b.getBrandId() == null;
        unique(() -> {
            if (isNew) {
                brandDao.insert(b);
            } else {
                brandDao.update(b);
            }
            return null;
        });
        audit(isNew, "Brands", b.getBrandId(), "ماركة " + b.getNameAr());
        return brandDao.findById(b.getBrandId()).orElseThrow();
    }

    @Override
    public void setBrandActive(int brandId, boolean active) {
        security.requirePermission(Permission.PRODUCTS);
        brandDao.setActive(brandId, active);
        auditActive(active, "Brands", brandId);
    }

    // ---------- Units ----------

    @Override
    public List<Unit> units(String text, boolean activeOnly) {
        security.requireAnyPermission(READ);
        return unitDao.search(text, activeOnly);
    }

    @Override
    public Unit saveUnit(Unit u) {
        security.requirePermission(Permission.PRODUCTS);
        u.setNameAr(trimToNull(u.getNameAr()));
        u.setNameEn(trimToNull(u.getNameEn()));
        u.setSymbol(trimToNull(u.getSymbol()));

        Validation v = new Validation();
        v.required(NAME_AR, u.getNameAr(), "اسم الوحدة مطلوب.");
        v.maxLength(NAME_AR, u.getNameAr(), 50, "اسم الوحدة");
        v.maxLength(NAME_EN, u.getNameEn(), 50, "الاسم بالإنجليزي");
        v.maxLength(SYMBOL, u.getSymbol(), 10, "الرمز");
        if (!v.has(NAME_AR) && unitDao.existsByName(u.getNameAr(), u.getUnitId())) {
            v.error(NAME_AR, "توجد وحدة أخرى بنفس الاسم \"" + u.getNameAr() + "\".");
        }
        v.throwIfAny();

        boolean isNew = u.getUnitId() == null;
        unique(() -> {
            if (isNew) {
                unitDao.insert(u);
            } else {
                unitDao.update(u);
            }
            return null;
        });
        audit(isNew, "Units", u.getUnitId(), "وحدة " + u.getNameAr());
        return unitDao.findById(u.getUnitId()).orElseThrow();
    }

    @Override
    public void setUnitActive(int unitId, boolean active) {
        security.requirePermission(Permission.PRODUCTS);
        unitDao.setActive(unitId, active);
        auditActive(active, "Units", unitId);
    }

    // ---------- Helpers ----------

    private static void unique(Supplier<Void> save) {
        try {
            save.get();
        } catch (DataAccessException e) {
            if (e.isDuplicateKey()) {
                throw new ValidationException(NAME_AR, "الاسم مستخدم مسبقًا.");
            }
            throw e;
        }
    }

    private void audit(boolean isNew, String table, Integer id, String what) {
        auditLogDao.log(security.currentUser().getUserId(), isNew ? AuditLogDao.INSERT : AuditLogDao.UPDATE,
                table, String.valueOf(id), (isNew ? "إضافة " : "تعديل ") + what);
    }

    private void auditActive(boolean active, String table, int id) {
        auditLogDao.log(security.currentUser().getUserId(), active ? AuditLogDao.ACTIVATE : AuditLogDao.DEACTIVATE,
                table, String.valueOf(id), active ? "تفعيل" : "تعطيل");
    }
}
