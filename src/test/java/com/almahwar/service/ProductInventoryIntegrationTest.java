package com.almahwar.service;

import com.almahwar.dao.AuditLogDao;
import com.almahwar.dao.BaseDao;
import com.almahwar.dao.BrandDao;
import com.almahwar.dao.CategoryDao;
import com.almahwar.dao.DashboardDao;
import com.almahwar.dao.ProductDao;
import com.almahwar.dao.RoleDao;
import com.almahwar.dao.StockMovementDao;
import com.almahwar.dao.TransactionManager;
import com.almahwar.dao.UnitDao;
import com.almahwar.dao.UserDao;
import com.almahwar.model.Brand;
import com.almahwar.model.Category;
import com.almahwar.model.MovementFilter;
import com.almahwar.model.MovementType;
import com.almahwar.model.Product;
import com.almahwar.model.ProductFilter;
import com.almahwar.model.Role;
import com.almahwar.model.StockAdjustment;
import com.almahwar.model.StockMovement;
import com.almahwar.model.Unit;
import com.almahwar.model.User;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Products & inventory against a real SQL Server database: transactions, opening stock,
 * adjustments, negative-stock protection, low stock and permissions. Enable with
 * {@code -Ddb.it=true} (see DaoIntegrationTest for the full command). Rows created here are removed.
 */
@EnabledIfSystemProperty(named = "db.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductInventoryIntegrationTest {

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    private final ProductDao productDao = new ProductDao();
    private final StockMovementDao movementDao = new StockMovementDao();
    private final StockLedger ledger = new StockLedger(movementDao);
    private final TestSecurity security = new TestSecurity();
    private final ProductService products = new ProductServiceImpl(productDao, new CategoryDao(), new BrandDao(),
            new UnitDao(), ledger, new AuditLogDao(), security);
    private final InventoryService inventory = new InventoryServiceImpl(productDao, new UnitDao(), movementDao,
            ledger, new AuditLogDao(), security);
    private final CatalogService catalog = new CatalogServiceImpl(new CategoryDao(), new BrandDao(), new UnitDao(),
            new AuditLogDao(), security);

    private final List<Integer> productIds = new ArrayList<>();
    private int userId;
    private Category category;
    private Brand brand;
    private Unit piece;
    private Unit metre;
    private int counter;

    /** Setup / cleanup SQL. */
    private static final class Sql extends BaseDao {
        int exec(String sql, Object... params) {
            return update(sql, params);
        }

        long count(String sql, Object... params) {
            return queryLong(sql, params);
        }
    }

    private final Sql sql = new Sql();

    @BeforeAll
    void createUserAndLookups() {
        User u = new User();
        u.setUsername("stock_" + suffix);
        u.setPasswordHash("pbkdf2_sha256$1$x$y");
        u.setFullName("أمين مخزن اختبار");
        u.setRoleId(new RoleDao().findByCode(Role.STOREKEEPER).orElseThrow().getRoleId());
        userId = new UserDao().insert(u);
        security.admin(userId);

        // "Add Category / Brand / Unit" through the same service the screens use
        Category c = new Category();
        c.setNameAr("خلاطات اختبار " + suffix);
        c.setNameEn("Test mixers");
        category = catalog.saveCategory(c);

        Brand b = new Brand();
        b.setNameAr("ماركة اختبار " + suffix);
        b.setCountry("إيطاليا");
        brand = catalog.saveBrand(b);

        Unit p = new Unit();
        p.setNameAr("قطعة " + suffix);
        piece = catalog.saveUnit(p);

        Unit m = new Unit();
        m.setNameAr("متر " + suffix);
        m.setAllowsDecimal(true);
        metre = catalog.saveUnit(m);
    }

    @BeforeEach
    void loginAsAdmin() {
        security.admin(userId);
    }

    @AfterAll
    void cleanUp() {
        for (Integer id : productIds) {
            movementDao.deleteByProduct(id);
            sql.exec("DELETE FROM dbo.Products WHERE product_id = ?", id);
        }
        sql.exec("DELETE FROM dbo.Categories WHERE name_ar LIKE ?", "%" + suffix + "%");
        sql.exec("DELETE FROM dbo.Brands WHERE name_ar LIKE ?", "%" + suffix + "%");
        sql.exec("DELETE FROM dbo.Units WHERE name_ar LIKE ?", "%" + suffix + "%");
        sql.exec("DELETE FROM dbo.Audit_Log WHERE user_id = ?", userId);
        sql.exec("DELETE FROM dbo.Users WHERE user_id = ?", userId);
    }

    private Product newProduct(String code) {
        Product p = new Product();
        p.setProductCode(code + "-" + suffix);
        p.setNameAr("خلاط مغسلة " + code);
        p.setNameEn("Basin mixer " + code);
        p.setCategoryId(category.getCategoryId());
        p.setBrandId(brand.getBrandId());
        p.setUnitId(piece.getUnitId());
        p.setSize("1/2\"");
        p.setColor("كروم");
        p.setPurchasePrice(new BigDecimal("8.250"));
        p.setSalePrice(new BigDecimal("12.500"));
        p.setWholesalePrice(new BigDecimal("11.000"));
        p.setMinimumStock(new BigDecimal("5"));
        p.setLocation("رف B-2");
        p.setNotes("ملاحظة اختبار");
        return p;
    }

    private Product create(Product p, String opening) {
        Product saved = products.create(p, opening == null ? null : new BigDecimal(opening));
        productIds.add(saved.getProductId());
        return saved;
    }

    private Product create(String code, String opening) {
        return create(newProduct(code + (++counter)), opening);
    }

    private void assertLedgerMatches(Product p) {
        BigDecimal quantity = productDao.findById(p.getProductId()).orElseThrow().getQuantity();
        assertEquals(0, quantity.compareTo(movementDao.sumForProduct(p.getProductId())),
                "product quantity must equal the sum of its stock movements");
    }

    private StockMovement adjust(Product p, MovementType type, String qty, String reason) {
        return inventory.adjust(new StockAdjustment(p.getProductId(), type, new BigDecimal(qty), reason));
    }

    // ---------- Add product ----------

    @Test
    void addProductSavesAllFields() {
        Product saved = create("ADD", null);
        assertNotNull(saved.getProductId());
        assertEquals("خلاط مغسلة ADD" + counter, saved.getNameAr());
        assertEquals(category.getNameAr(), saved.getCategoryName());
        assertEquals(brand.getNameAr(), saved.getBrandName());
        assertEquals(piece.getNameAr(), saved.getUnitName());
        assertEquals(new BigDecimal("12.500"), saved.getSalePrice());
        assertEquals(new BigDecimal("8.250"), saved.getPurchasePrice());
        assertEquals("ملاحظة اختبار", saved.getNotes());
        assertEquals("رف B-2", saved.getLocation());
        assertTrue(saved.isActive());
        assertNotNull(saved.getCreatedAt());
        assertEquals(new BigDecimal("0.000"), saved.getQuantity());
        assertTrue(movementDao.findByProduct(saved.getProductId()).isEmpty(), "no opening stock, no movement");
        assertEquals(1, sql.count("SELECT COUNT(*) FROM dbo.Audit_Log WHERE user_id = ? AND table_name = 'Products' "
                + "AND record_id = ? AND action = 'INSERT'", userId, String.valueOf(saved.getProductId())));
    }

    @Test
    void duplicateProductCodeIsRejected() {
        Product first = create("DUP", null);
        Product again = newProduct("X");
        again.setProductCode(first.getProductCode().toLowerCase());   // collation is case-insensitive
        ValidationException e = assertThrows(ValidationException.class, () -> create(again, "3"));
        assertTrue(e.errorFor(ProductService.PRODUCT_CODE).contains("مستخدم لمنتج آخر"));
    }

    @Test
    void duplicateBarcodeIsRejectedButEmptyBarcodesAreFine() {
        Product a = newProduct("BC" + (++counter));
        a.setBarcode("629" + suffix);
        create(a, null);
        Product b = newProduct("BC" + (++counter));
        b.setBarcode(" 629" + suffix + " ");
        ValidationException e = assertThrows(ValidationException.class, () -> create(b, null));
        assertTrue(e.errorFor(ProductService.BARCODE).contains("مستخدم لمنتج آخر"));

        Product c = newProduct("NB" + (++counter));
        c.setBarcode("   ");
        Product d = newProduct("NB" + (++counter));
        assertNull(create(c, null).getBarcode());
        assertNull(create(d, null).getBarcode());
    }

    @Test
    void invalidPriceIsRejectedAndNothingIsSaved() {
        Product p = newProduct("NEG" + (++counter));
        p.setSalePrice(new BigDecimal("-1"));
        ValidationException e = assertThrows(ValidationException.class, () -> create(p, "10"));
        assertEquals("سعر البيع يجب أن يكون صفرًا أو أكثر.", e.errorFor(ProductService.SALE_PRICE));
        assertTrue(productDao.findByCode(p.getProductCode()).isEmpty());
    }

    // ---------- Opening stock ----------

    @Test
    void openingStockCreatesOpeningBalanceMovementInTheSameTransaction() {
        Product saved = create("OPEN", "25");
        assertEquals(new BigDecimal("25.000"), saved.getQuantity());

        List<StockMovement> moves = movementDao.findByProduct(saved.getProductId());
        assertEquals(1, moves.size());
        StockMovement m = moves.get(0);
        assertEquals(MovementType.OPENING_BALANCE, m.getMovementType());
        assertEquals(new BigDecimal("0.000"), m.getQuantityBefore());
        assertEquals(new BigDecimal("25.000"), m.getQuantity());
        assertEquals(new BigDecimal("25.000"), m.getQuantityAfter());
        assertEquals(new BigDecimal("8.250"), m.getUnitCost());
        assertEquals("PRODUCT", m.getReferenceType());
        assertEquals(saved.getProductId(), m.getReferenceId());
        assertEquals(userId, m.getUserId());
        assertNotNull(m.getMovementDate());
        assertLedgerMatches(saved);
    }

    @Test
    void fractionalOpeningStockOnlyForDecimalUnits() {
        Product pieces = newProduct("FR" + (++counter));
        ValidationException e = assertThrows(ValidationException.class, () -> create(pieces, "2.5"));
        assertTrue(e.errorFor(ProductService.OPENING_QUANTITY).contains("لا تقبل الكسور"));

        Product pipe = newProduct("FR" + (++counter));
        pipe.setUnitId(metre.getUnitId());
        assertEquals(new BigDecimal("120.750"), create(pipe, "120.75").getQuantity());
    }

    @Test
    void failedTransactionLeavesNoProductAndNoMovement() {
        Product p = newProduct("RB" + (++counter));
        assertThrows(IllegalStateException.class, () -> TransactionManager.inTransaction(con -> {
            int id = productDao.insert(con, p);
            ledger.post(con, id, MovementType.OPENING_BALANCE, BigDecimal.TEN, "PRODUCT", id, "x", userId);
            throw new IllegalStateException("simulated failure after the movement");
        }));
        assertTrue(productDao.findByCode(p.getProductCode()).isEmpty());
        assertEquals(0, sql.count("SELECT COUNT(*) FROM dbo.Stock_Movements m JOIN dbo.Products p "
                + "ON p.product_id = m.product_id WHERE p.product_code = ?", p.getProductCode()));
    }

    @Test
    void editingAProductNeverChangesItsQuantity() {
        Product saved = create("EDIT", "7");
        saved.setNameAr("اسم معدل " + suffix);
        saved.setSalePrice(new BigDecimal("13.750"));
        saved.setQuantity(new BigDecimal("999"));   // ignored
        Product updated = products.update(saved);
        assertEquals("اسم معدل " + suffix, updated.getNameAr());
        assertEquals(new BigDecimal("13.750"), updated.getSalePrice());
        assertEquals(new BigDecimal("7.000"), updated.getQuantity());
        assertLedgerMatches(updated);
    }

    // ---------- Adjustments ----------

    @Test
    void adjustmentInAddsStockAndRecordsUserAndReason() {
        Product p = create("IN", "10");
        StockMovement m = adjust(p, MovementType.ADJUSTMENT_IN, "4", "جرد المخزن");
        assertEquals(MovementType.ADJUSTMENT_IN, m.getMovementType());
        assertEquals(new BigDecimal("10.000"), m.getQuantityBefore());
        assertEquals(new BigDecimal("4.000"), m.getQuantity());
        assertEquals(new BigDecimal("14.000"), m.getQuantityAfter());
        assertEquals(new BigDecimal("14.000"), productDao.findById(p.getProductId()).orElseThrow().getQuantity());

        StockMovement stored = movementDao.findByProduct(p.getProductId()).get(1);
        assertEquals("جرد المخزن", stored.getReason());
        assertEquals(userId, stored.getUserId());
        assertEquals("أمين مخزن اختبار", stored.getUserName());
        assertNotNull(stored.getMovementDate());
        assertNull(stored.getReferenceType());
        assertLedgerMatches(p);
        assertEquals(1, sql.count("SELECT COUNT(*) FROM dbo.Audit_Log WHERE user_id = ? AND action = 'STOCK_ADJUSTMENT' "
                + "AND record_id = ?", userId, String.valueOf(m.getMovementId())));
    }

    @Test
    void adjustmentOutDeductsStock() {
        Product p = create("OUT", "10");
        StockMovement m = adjust(p, MovementType.ADJUSTMENT_OUT, "3", "تالف");
        assertEquals(new BigDecimal("-3.000"), m.getQuantity());
        assertEquals(new BigDecimal("10.000"), m.getQuantityBefore());
        assertEquals(new BigDecimal("7.000"), m.getQuantityAfter());
        assertEquals(new BigDecimal("7.000"), productDao.findById(p.getProductId()).orElseThrow().getQuantity());

        StockMovement last = adjust(p, MovementType.ADJUSTMENT_OUT, "7", "فرق كمية");
        assertEquals(new BigDecimal("0.000"), last.getQuantityAfter(), "down to exactly zero is allowed");
        assertLedgerMatches(p);
    }

    @Test
    void stockCanNeverGoNegative() {
        Product p = create("NEGSTK", "5");
        ValidationException e = assertThrows(ValidationException.class,
                () -> adjust(p, MovementType.ADJUSTMENT_OUT, "6", "تالف"));
        assertTrue(e.errorFor(InventoryService.QUANTITY).contains("بالسالب"));
        assertEquals(new BigDecimal("5.000"), productDao.findById(p.getProductId()).orElseThrow().getQuantity());
        assertEquals(1, movementDao.findByProduct(p.getProductId()).size(), "the refused change left no movement");

        // The database refuses it too, even if a future module forgets the check
        assertThrows(RuntimeException.class, () -> sql.exec(
                "UPDATE dbo.Products SET quantity = -1 WHERE product_id = ?", p.getProductId()));
        assertLedgerMatches(p);
    }

    @Test
    void adjustmentNeedsAReasonAndAnActiveProduct() {
        Product p = create("RSN", "5");
        ValidationException e = assertThrows(ValidationException.class,
                () -> adjust(p, MovementType.ADJUSTMENT_IN, "1", " "));
        assertEquals("سبب التسوية مطلوب.", e.errorFor(InventoryService.REASON));

        products.setActive(p.getProductId(), false);
        e = assertThrows(ValidationException.class, () -> adjust(p, MovementType.ADJUSTMENT_IN, "1", "جرد"));
        assertTrue(e.errorFor(InventoryService.PRODUCT).contains("معطّل"));
        assertEquals(1, movementDao.findByProduct(p.getProductId()).size());
    }

    // ---------- Low stock & dashboard ----------

    @Test
    void lowStockIsDetectedAndShownOnTheDashboard() {
        DashboardDao dashboard = new DashboardDao();
        long before = dashboard.loadStats().lowStockProducts();

        Product low = create("LOW", "3");   // minimum 5
        Product ok = create("OK", "50");
        assertEquals(before + 1, dashboard.loadStats().lowStockProducts());
        assertEquals(before + 1, products.countLowStock());

        List<Product> lowList = products.search(ProductFilter.all().withLowStockOnly(true));
        assertTrue(lowList.stream().anyMatch(p -> p.getProductId().equals(low.getProductId())));
        assertFalse(lowList.stream().anyMatch(p -> p.getProductId().equals(ok.getProductId())));

        adjust(low, MovementType.ADJUSTMENT_IN, "2", "استلام");   // 5 = minimum: still low
        assertEquals(before + 1, dashboard.loadStats().lowStockProducts());
        adjust(low, MovementType.ADJUSTMENT_IN, "1", "استلام");   // 6 > minimum
        assertEquals(before, dashboard.loadStats().lowStockProducts());
    }

    // ---------- Search, history, permissions ----------

    @Test
    void searchByNameCodeAndBarcodeWithFilters() {
        Product p = newProduct("SRCH" + (++counter));
        p.setBarcode("777" + suffix);
        Product saved = create(p, "1");

        for (String text : List.of("خلاط مغسلة SRCH", saved.getProductCode(), "777" + suffix)) {
            assertTrue(products.search(ProductFilter.search(text)).stream()
                    .anyMatch(x -> x.getProductId().equals(saved.getProductId())), text);
        }
        ProductFilter byLookups = new ProductFilter(null, category.getCategoryId(), brand.getBrandId(),
                piece.getUnitId(), ProductFilter.ActiveStatus.ACTIVE, false);
        assertTrue(products.search(byLookups).stream().allMatch(x -> x.getCategoryId().equals(category.getCategoryId())));
        assertTrue(products.search(byLookups).stream().anyMatch(x -> x.getProductId().equals(saved.getProductId())));
    }

    @Test
    void historyFiltersByProductTypeAndDate() {
        Product p = create("HIS", "20");
        adjust(p, MovementType.ADJUSTMENT_OUT, "2", "تالف");
        adjust(p, MovementType.ADJUSTMENT_IN, "1", "تصحيح إدخال");

        List<StockMovement> all = inventory.history(MovementFilter.forProduct(p.getProductId()));
        assertEquals(3, all.size());
        assertEquals(MovementType.ADJUSTMENT_IN, all.get(0).getMovementType(), "newest first");

        LocalDate today = new DashboardDao().serverDate();
        assertEquals(1, inventory.history(new MovementFilter(p.getProductId(), MovementType.ADJUSTMENT_OUT,
                today, today)).size());
        assertEquals(0, inventory.history(new MovementFilter(p.getProductId(), null,
                today.plusDays(1), null)).size());
        assertThrows(ValidationException.class, () -> inventory.history(new MovementFilter(null, null,
                today, today.minusDays(1))));
    }

    @Test
    void cashierSeesActiveProductsWithoutCostAndCannotAdjust() {
        Product active = create("CSH", "9");
        Product inactive = create("CSH", "1");
        products.setActive(inactive.getProductId(), false);

        security.as(Role.CASHIER, userId);
        List<Product> seen = products.search(new ProductFilter(null, category.getCategoryId(), null, null,
                ProductFilter.ActiveStatus.ALL, false));
        assertTrue(seen.stream().anyMatch(p -> p.getProductId().equals(active.getProductId())));
        assertFalse(seen.stream().anyMatch(p -> p.getProductId().equals(inactive.getProductId())),
                "view-only users get active products only");
        assertTrue(seen.stream().allMatch(p -> p.getPurchasePrice() == null), "no purchase cost for the cashier");
        assertNotNull(seen.get(0).getSalePrice());
        assertThrows(AccessDeniedException.class,
                () -> adjust(active, MovementType.ADJUSTMENT_OUT, "1", "تالف"));

        security.as(Role.ACCOUNTANT, userId);
        assertThrows(AccessDeniedException.class,
                () -> adjust(active, MovementType.ADJUSTMENT_IN, "1", "جرد"));

        security.as(Role.STOREKEEPER, userId);
        assertEquals(new BigDecimal("10.000"),
                adjust(active, MovementType.ADJUSTMENT_IN, "1", "جرد").getQuantityAfter());
        assertLedgerMatches(active);
    }

    @Test
    void catalogNamesAreUniqueAndCanBeDeactivated() {
        Category dup = new Category();
        dup.setNameAr(category.getNameAr());
        ValidationException e = assertThrows(ValidationException.class, () -> catalog.saveCategory(dup));
        assertTrue(e.errorFor(CatalogService.NAME_AR).contains("بنفس الاسم"));

        Brand extra = new Brand();
        extra.setNameAr("ماركة معطلة " + suffix);
        Brand saved = catalog.saveBrand(extra);
        catalog.setBrandActive(saved.getBrandId(), false);
        assertFalse(catalog.brands(suffix, true).stream().anyMatch(b -> b.getBrandId().equals(saved.getBrandId())));
        assertTrue(catalog.brands(suffix, false).stream().anyMatch(b -> b.getBrandId().equals(saved.getBrandId())));

        Product p = newProduct("INB" + (++counter));
        p.setBrandId(saved.getBrandId());
        ValidationException inactive = assertThrows(ValidationException.class, () -> create(p, null));
        assertTrue(inactive.errorFor(ProductService.BRAND).contains("معطّلة"));
    }
}
