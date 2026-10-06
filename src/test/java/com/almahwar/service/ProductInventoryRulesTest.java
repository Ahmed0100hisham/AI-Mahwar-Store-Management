package com.almahwar.service;

import com.almahwar.dao.AuditLogDao;
import com.almahwar.dao.BrandDao;
import com.almahwar.dao.CategoryDao;
import com.almahwar.dao.ProductDao;
import com.almahwar.dao.StockMovementDao;
import com.almahwar.dao.UnitDao;
import com.almahwar.model.Category;
import com.almahwar.model.MovementType;
import com.almahwar.model.Permission;
import com.almahwar.model.Product;
import com.almahwar.model.ProductFilter;
import com.almahwar.model.Role;
import com.almahwar.model.StockAdjustment;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validation and permission rules of the product and inventory services. All of these are
 * decided before the database is touched, so they run without SQL Server.
 */
class ProductInventoryRulesTest {

    private final TestSecurity security = new TestSecurity();
    private final StockLedger ledger = new StockLedger(new StockMovementDao());
    private final ProductService products = new ProductServiceImpl(new ProductDao(), new CategoryDao(),
            new BrandDao(), new UnitDao(), ledger, new AuditLogDao(), security);
    private final InventoryService inventory = new InventoryServiceImpl(new ProductDao(), new UnitDao(),
            new StockMovementDao(), ledger, new AuditLogDao(), security);
    private final CatalogService catalog = new CatalogServiceImpl(new CategoryDao(), new BrandDao(), new UnitDao(),
            new AuditLogDao(), security);

    private static Product validProduct() {
        Product p = new Product();
        p.setProductCode("P-1");
        p.setNameAr("محبس زاوية");
        p.setCategoryId(1);
        p.setUnitId(1);
        p.setPurchasePrice(new BigDecimal("1.250"));
        p.setSalePrice(new BigDecimal("2.000"));
        p.setWholesalePrice(new BigDecimal("1.800"));
        p.setMinimumStock(new BigDecimal("5"));
        return p;
    }

    // ---------- Product validation ----------

    @Test
    void validProductPassesFieldValidation() {
        ProductServiceImpl.validateFields(validProduct()).throwIfAny();
    }

    @Test
    void codeAndArabicNameAreRequired() {
        Product p = validProduct();
        p.setProductCode(null);
        p.setNameAr(null);
        p.setCategoryId(null);
        p.setUnitId(null);
        ValidationException e = assertThrows(ValidationException.class,
                () -> ProductServiceImpl.validateFields(p).throwIfAny());
        assertEquals("كود المنتج مطلوب.", e.errorFor(ProductService.PRODUCT_CODE));
        assertEquals("اسم المنتج بالعربي مطلوب.", e.errorFor(ProductService.NAME_AR));
        assertEquals("اختر القسم.", e.errorFor(ProductService.CATEGORY));
        assertEquals("اختر الوحدة.", e.errorFor(ProductService.UNIT));
    }

    @Test
    void invalidPricesAreRejectedWithArabicMessages() {
        security.as(Role.STOREKEEPER, 1);
        Product p = validProduct();
        p.setPurchasePrice(new BigDecimal("-0.001"));
        p.setSalePrice(new BigDecimal("-5"));
        p.setWholesalePrice(new BigDecimal("-1"));
        p.setMinimumStock(new BigDecimal("-2"));

        ValidationException e = assertThrows(ValidationException.class,
                () -> products.create(p, new BigDecimal("-3")));
        assertEquals("سعر الشراء يجب أن يكون صفرًا أو أكثر.", e.errorFor(ProductService.PURCHASE_PRICE));
        assertEquals("سعر البيع يجب أن يكون صفرًا أو أكثر.", e.errorFor(ProductService.SALE_PRICE));
        assertEquals("سعر الجملة يجب أن يكون صفرًا أو أكثر.", e.errorFor(ProductService.WHOLESALE_PRICE));
        assertEquals("الحد الأدنى للمخزون يجب أن يكون صفرًا أو أكثر.", e.errorFor(ProductService.MINIMUM_STOCK));
        assertEquals("الكمية الافتتاحية يجب أن تكون صفرًا أو أكثر.", e.errorFor(ProductService.OPENING_QUANTITY));
    }

    @Test
    void zeroPricesAreAllowed() {
        Product p = validProduct();
        p.setPurchasePrice(BigDecimal.ZERO);
        p.setSalePrice(BigDecimal.ZERO);
        p.setWholesalePrice(BigDecimal.ZERO);
        ProductServiceImpl.validateFields(p).throwIfAny();
    }

    @Test
    void tooLongTextIsRejected() {
        Product p = validProduct();
        p.setProductCode("X".repeat(31));
        ValidationException e = assertThrows(ValidationException.class,
                () -> ProductServiceImpl.validateFields(p).throwIfAny());
        assertTrue(e.errorFor(ProductService.PRODUCT_CODE).contains("30"));
    }

    // ---------- Stock adjustment validation ----------

    @Test
    void adjustmentNeedsProductQuantityAndReason() {
        security.as(Role.STOREKEEPER, 1);
        ValidationException e = assertThrows(ValidationException.class, () -> inventory.adjust(
                new StockAdjustment(null, MovementType.ADJUSTMENT_IN, BigDecimal.ZERO, "   ")));
        assertEquals("اختر المنتج.", e.errorFor(InventoryService.PRODUCT));
        assertEquals("الكمية يجب أن تكون أكبر من صفر.", e.errorFor(InventoryService.QUANTITY));
        assertEquals("سبب التسوية مطلوب.", e.errorFor(InventoryService.REASON));
    }

    @Test
    void onlyAdjustmentTypesCanBeEnteredByHand() {
        for (MovementType type : MovementType.values()) {
            StockAdjustment request = new StockAdjustment(1, type, BigDecimal.ONE, "جرد المخزن");
            boolean rejected = InventoryServiceImpl.validate(request, request.reason()).has(InventoryService.TYPE);
            assertEquals(!type.isManualAdjustment(), rejected, type.name());
        }
        security.as(Role.STOREKEEPER, 1);
        ValidationException e = assertThrows(ValidationException.class, () -> inventory.adjust(
                new StockAdjustment(1, MovementType.OPENING_BALANCE, BigDecimal.ONE, "x")));
        assertTrue(e.errorFor(InventoryService.TYPE).contains("رصيد افتتاحي"));
    }

    @Test
    void negativeAdjustmentQuantityIsRejected() {
        security.as(Role.STOREKEEPER, 1);
        ValidationException e = assertThrows(ValidationException.class, () -> inventory.adjust(
                new StockAdjustment(1, MovementType.ADJUSTMENT_OUT, new BigDecimal("-4"), "تالف")));
        assertEquals("الكمية يجب أن تكون أكبر من صفر.", e.errorFor(InventoryService.QUANTITY));
    }

    @Test
    void movementDirections() {
        assertTrue(MovementType.OPENING_BALANCE.isIncoming());
        assertTrue(MovementType.PURCHASE.isIncoming());
        assertTrue(MovementType.SALE_RETURN.isIncoming());
        assertTrue(MovementType.ADJUSTMENT_IN.isIncoming());
        assertFalse(MovementType.SALE.isIncoming());
        assertFalse(MovementType.PURCHASE_RETURN.isIncoming());
        assertFalse(MovementType.ADJUSTMENT_OUT.isIncoming());
    }

    // ---------- Low stock ----------

    @Test
    void lowStockMeansQuantityAtOrBelowMinimum() {
        Product p = validProduct();
        p.setMinimumStock(new BigDecimal("5"));
        p.setQuantity(new BigDecimal("5"));
        assertTrue(p.isLowStock(), "equal to the minimum is low");
        p.setQuantity(new BigDecimal("4.999"));
        assertTrue(p.isLowStock());
        p.setQuantity(new BigDecimal("5.001"));
        assertFalse(p.isLowStock());
    }

    // ---------- Permissions ----------

    @Test
    void roleMatrixForProductsAndInventory() {
        Set<Permission> cashier = RolePermissions.forRole(Role.CASHIER);
        assertTrue(cashier.contains(Permission.PRODUCTS_VIEW));
        assertFalse(cashier.contains(Permission.PRODUCTS));
        assertFalse(cashier.contains(Permission.PRODUCT_COST));
        assertFalse(cashier.contains(Permission.INVENTORY_ADJUST));

        Set<Permission> storekeeper = RolePermissions.forRole(Role.STOREKEEPER);
        assertTrue(storekeeper.containsAll(Set.of(Permission.PRODUCTS_VIEW, Permission.PRODUCTS,
                Permission.PRODUCT_COST, Permission.INVENTORY, Permission.INVENTORY_ADJUST)));

        Set<Permission> accountant = RolePermissions.forRole(Role.ACCOUNTANT);
        assertFalse(accountant.contains(Permission.INVENTORY_ADJUST));
        assertFalse(accountant.contains(Permission.PRODUCTS));
    }

    @Test
    void cashierCannotChangeProductsOrStock() {
        security.as(Role.CASHIER, 1);
        Product saved = validProduct();
        saved.setProductId(1);
        assertThrows(AccessDeniedException.class, () -> products.create(validProduct(), BigDecimal.TEN));
        assertThrows(AccessDeniedException.class, () -> products.update(saved));
        assertThrows(AccessDeniedException.class, () -> products.setActive(1, false));
        assertThrows(AccessDeniedException.class, () -> inventory.adjust(
                new StockAdjustment(1, MovementType.ADJUSTMENT_IN, BigDecimal.ONE, "جرد المخزن")));
        assertThrows(AccessDeniedException.class, () -> inventory.history(null));
        assertThrows(AccessDeniedException.class, () -> catalog.saveCategory(new Category()));
    }

    @Test
    void accountantCannotAdjustStock() {
        security.as(Role.ACCOUNTANT, 1);
        assertThrows(AccessDeniedException.class, () -> inventory.adjust(
                new StockAdjustment(1, MovementType.ADJUSTMENT_OUT, BigDecimal.ONE, "تالف")));
        assertThrows(AccessDeniedException.class, () -> products.create(validProduct(), null));
    }

    @Test
    void nothingWithoutLoginOrWithoutRole() {
        security.logout();
        assertThrows(AccessDeniedException.class, () -> products.search(ProductFilter.all()));
        assertThrows(AccessDeniedException.class, () -> inventory.stockList(null));
        security.as(Role.MANAGER, 1);   // seeded role without permissions
        assertThrows(AccessDeniedException.class, () -> products.search(ProductFilter.all()));
        assertThrows(AccessDeniedException.class, () -> catalog.categories(null, true));
    }

    @Test
    void validationMessagesAreKeptPerField() {
        ValidationException e = new ValidationException(Map.of("a", "خطأ"));
        assertEquals("خطأ", e.getMessage());
        assertNull(e.errorFor("b"));
    }
}
