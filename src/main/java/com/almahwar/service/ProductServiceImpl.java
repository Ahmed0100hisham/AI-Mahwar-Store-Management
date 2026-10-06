package com.almahwar.service;

import com.almahwar.dao.AuditLogDao;
import com.almahwar.dao.BrandDao;
import com.almahwar.dao.CategoryDao;
import com.almahwar.dao.DataAccessException;
import com.almahwar.dao.ProductDao;
import com.almahwar.dao.TransactionManager;
import com.almahwar.dao.UnitDao;
import com.almahwar.model.Brand;
import com.almahwar.model.Category;
import com.almahwar.model.MovementType;
import com.almahwar.model.Permission;
import com.almahwar.model.Product;
import com.almahwar.model.ProductFilter;
import com.almahwar.model.ProductFilter.ActiveStatus;
import com.almahwar.model.Unit;
import com.almahwar.util.MoneyUtil;
import com.almahwar.util.QuantityUtil;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static com.almahwar.service.Validation.trimToNull;

/** Product management on SQL Server through the DAOs. */
public class ProductServiceImpl implements ProductService {

    static final String TABLE = "Products";
    static final String OPENING_REASON = "رصيد افتتاحي";

    private final ProductDao productDao;
    private final CategoryDao categoryDao;
    private final BrandDao brandDao;
    private final UnitDao unitDao;
    private final StockLedger ledger;
    private final AuditLogDao auditLogDao;
    private final SecurityContext security;

    public ProductServiceImpl(ProductDao productDao, CategoryDao categoryDao, BrandDao brandDao, UnitDao unitDao,
                              StockLedger ledger, AuditLogDao auditLogDao, SecurityContext security) {
        this.productDao = productDao;
        this.categoryDao = categoryDao;
        this.brandDao = brandDao;
        this.unitDao = unitDao;
        this.ledger = ledger;
        this.auditLogDao = auditLogDao;
        this.security = security;
    }

    // ---------- Reading ----------

    @Override
    public List<Product> search(ProductFilter filter) {
        security.requireAnyPermission(Permission.PRODUCTS_VIEW, Permission.PRODUCTS);
        ProductFilter f = filter == null ? ProductFilter.all() : filter;
        if (!security.hasPermission(Permission.PRODUCTS)) {
            f = f.withStatus(ActiveStatus.ACTIVE);   // view-only users work with sellable products
        }
        return hideCostIfNeeded(productDao.search(f));
    }

    @Override
    public Optional<Product> findById(int productId) {
        security.requireAnyPermission(Permission.PRODUCTS_VIEW, Permission.PRODUCTS);
        return productDao.findById(productId)
                .filter(p -> p.isActive() || security.hasPermission(Permission.PRODUCTS))
                .map(p -> hideCostIfNeeded(List.of(p)).get(0));
    }

    @Override
    public long countLowStock() {
        security.requireAnyPermission(Permission.PRODUCTS_VIEW, Permission.PRODUCTS, Permission.INVENTORY);
        return productDao.countLowStock();
    }

    private List<Product> hideCostIfNeeded(List<Product> products) {
        if (!security.hasPermission(Permission.PRODUCT_COST)) {
            products.forEach(p -> p.setPurchasePrice(null));
        }
        return products;
    }

    // ---------- Writing ----------

    @Override
    public Product create(Product product, BigDecimal openingQuantity) {
        security.requirePermission(Permission.PRODUCTS);
        int userId = security.currentUser().getUserId();
        normalize(product);
        if (!security.hasPermission(Permission.PRODUCT_COST)) {
            product.setPurchasePrice(MoneyUtil.ZERO);
        }
        BigDecimal opening = openingQuantity == null ? QuantityUtil.ZERO : openingQuantity;

        Validation v = validateFields(product);
        v.nonNegative(OPENING_QUANTITY, opening, "الكمية الافتتاحية", true);
        v.throwIfAny();
        validateReferences(product, null, v);
        v.throwIfAny();
        Unit unit = unitDao.findById(product.getUnitId()).orElseThrow();
        v.wholeNumberUnless(unit.isAllowsDecimal(), OPENING_QUANTITY, opening, unit.getNameAr());
        v.throwIfAny();

        product.setQuantity(QuantityUtil.ZERO);   // stock only through the ledger
        int productId = saveUnique(() -> TransactionManager.inTransaction(con -> {
            int id = productDao.insert(con, product);
            if (opening.signum() > 0) {
                ledger.post(con, id, MovementType.OPENING_BALANCE, opening, "PRODUCT", id, OPENING_REASON, userId);
            }
            auditLogDao.log(con, userId, AuditLogDao.INSERT, TABLE, String.valueOf(id),
                    "إضافة منتج " + product.getProductCode() + " - " + product.getNameAr()
                            + " برصيد افتتاحي " + QuantityUtil.format(opening));
            return id;
        }));
        return productDao.findById(productId).orElseThrow();
    }

    @Override
    public Product update(Product product) {
        security.requirePermission(Permission.PRODUCTS);
        if (product.getProductId() == null) {
            throw new IllegalArgumentException("update() needs a saved product; use create()");
        }
        int userId = security.currentUser().getUserId();
        Product existing = productDao.findById(product.getProductId())
                .orElseThrow(() -> new ValidationException("productId", "المنتج غير موجود."));
        normalize(product);
        if (!security.hasPermission(Permission.PRODUCT_COST)) {
            product.setPurchasePrice(existing.getPurchasePrice());   // cannot see it, so cannot change it
        }

        Validation v = validateFields(product);
        v.throwIfAny();
        validateReferences(product, existing, v);
        v.throwIfAny();

        saveUnique(() -> TransactionManager.inTransaction(con -> {
            productDao.update(con, product);
            auditLogDao.log(con, userId, AuditLogDao.UPDATE, TABLE, String.valueOf(product.getProductId()),
                    "تعديل منتج " + product.getProductCode() + " - " + product.getNameAr());
            return product.getProductId();
        }));
        return productDao.findById(product.getProductId()).orElseThrow();
    }

    @Override
    public void setActive(int productId, boolean active) {
        security.requirePermission(Permission.PRODUCTS);
        int userId = security.currentUser().getUserId();
        Product existing = productDao.findById(productId)
                .orElseThrow(() -> new ValidationException("productId", "المنتج غير موجود."));
        TransactionManager.inTransaction(con -> {
            productDao.update(con, withActive(existing, active));
            auditLogDao.log(con, userId, active ? AuditLogDao.ACTIVATE : AuditLogDao.DEACTIVATE, TABLE,
                    String.valueOf(productId), (active ? "تفعيل" : "تعطيل") + " منتج " + existing.getProductCode());
            return null;
        });
    }

    private static Product withActive(Product p, boolean active) {
        p.setActive(active);
        return p;
    }

    // ---------- Validation ----------

    private static void normalize(Product p) {
        p.setProductCode(trimToNull(p.getProductCode()));
        p.setBarcode(trimToNull(p.getBarcode()));
        p.setNameAr(trimToNull(p.getNameAr()));
        p.setNameEn(trimToNull(p.getNameEn()));
        p.setSize(trimToNull(p.getSize()));
        p.setColor(trimToNull(p.getColor()));
        p.setLocation(trimToNull(p.getLocation()));
        p.setNotes(trimToNull(p.getNotes()));
    }

    /** Checks that need no database; package-private for unit tests. */
    static Validation validateFields(Product p) {
        Validation v = new Validation();
        v.required(PRODUCT_CODE, p.getProductCode(), "كود المنتج مطلوب.");
        v.maxLength(PRODUCT_CODE, p.getProductCode(), 30, "كود المنتج");
        v.maxLength(BARCODE, p.getBarcode(), 50, "الباركود");
        v.required(NAME_AR, p.getNameAr(), "اسم المنتج بالعربي مطلوب.");
        v.maxLength(NAME_AR, p.getNameAr(), 200, "اسم المنتج بالعربي");
        v.maxLength(NAME_EN, p.getNameEn(), 200, "اسم المنتج بالإنجليزي");
        if (p.getCategoryId() == null) {
            v.error(CATEGORY, "اختر القسم.");
        }
        if (p.getUnitId() == null) {
            v.error(UNIT, "اختر الوحدة.");
        }
        v.maxLength(SIZE, p.getSize(), 50, "المقاس");
        v.maxLength(COLOR, p.getColor(), 50, "اللون");
        v.nonNegative(PURCHASE_PRICE, p.getPurchasePrice(), "سعر الشراء");
        v.nonNegative(SALE_PRICE, p.getSalePrice(), "سعر البيع");
        v.nonNegative(WHOLESALE_PRICE, p.getWholesalePrice(), "سعر الجملة");
        v.nonNegative(MINIMUM_STOCK, p.getMinimumStock(), "الحد الأدنى للمخزون");
        v.maxLength(LOCATION, p.getLocation(), 100, "مكان التخزين");
        v.maxLength(NOTES, p.getNotes(), 500, "الملاحظات");
        return v;
    }

    /** Uniqueness and lookups; inactive lookups are refused unless the product already used them. */
    private void validateReferences(Product p, Product existing, Validation v) {
        if (productDao.existsByCode(p.getProductCode(), p.getProductId())) {
            v.error(PRODUCT_CODE, "كود المنتج \"" + p.getProductCode() + "\" مستخدم لمنتج آخر.");
        }
        if (p.getBarcode() != null && productDao.existsByBarcode(p.getBarcode(), p.getProductId())) {
            v.error(BARCODE, "الباركود \"" + p.getBarcode() + "\" مستخدم لمنتج آخر.");
        }
        Optional<Category> category = categoryDao.findById(p.getCategoryId());
        if (category.isEmpty()) {
            v.error(CATEGORY, "القسم المختار غير موجود.");
        } else if (!category.get().isActive() && (existing == null || !p.getCategoryId().equals(existing.getCategoryId()))) {
            v.error(CATEGORY, "القسم \"" + category.get().getNameAr() + "\" معطّل.");
        }
        Optional<Unit> unit = unitDao.findById(p.getUnitId());
        if (unit.isEmpty()) {
            v.error(UNIT, "الوحدة المختارة غير موجودة.");
        } else {
            if (!unit.get().isActive() && (existing == null || !p.getUnitId().equals(existing.getUnitId()))) {
                v.error(UNIT, "الوحدة \"" + unit.get().getNameAr() + "\" معطّلة.");
            }
            v.wholeNumberUnless(unit.get().isAllowsDecimal(), MINIMUM_STOCK, p.getMinimumStock(), unit.get().getNameAr());
        }
        if (p.getBrandId() != null) {
            Optional<Brand> brand = brandDao.findById(p.getBrandId());
            if (brand.isEmpty()) {
                v.error(BRAND, "الماركة المختارة غير موجودة.");
            } else if (!brand.get().isActive() && (existing == null || !p.getBrandId().equals(existing.getBrandId()))) {
                v.error(BRAND, "الماركة \"" + brand.get().getNameAr() + "\" معطّلة.");
            }
        }
    }

    /** Another user may save the same code/barcode between our check and the insert; the DB index decides. */
    private static <T> T saveUnique(java.util.function.Supplier<T> save) {
        try {
            return save.get();
        } catch (DataAccessException e) {
            if (e.violates("UQ_Products_product_code")) {
                throw new ValidationException(PRODUCT_CODE, "كود المنتج مستخدم لمنتج آخر.");
            }
            if (e.violates("UX_Products_barcode")) {
                throw new ValidationException(BARCODE, "الباركود مستخدم لمنتج آخر.");
            }
            throw e;
        }
    }
}
