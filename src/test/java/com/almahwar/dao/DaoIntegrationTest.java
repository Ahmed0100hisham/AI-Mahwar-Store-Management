package com.almahwar.dao;

import com.almahwar.model.Brand;
import com.almahwar.model.Category;
import com.almahwar.model.Customer;
import com.almahwar.model.CustomerType;
import com.almahwar.model.Product;
import com.almahwar.model.Role;
import com.almahwar.model.Supplier;
import com.almahwar.model.Unit;
import com.almahwar.model.User;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the DAOs against a real SQL Server database created by
 * {@code database/01_create_database.sql}. Skipped unless enabled:
 * <pre>
 * mvn test -Ddb.it=true -Ddb.host=localhost -Ddb.port=1433 -Ddb.user=sa -Ddb.password=...
 * </pre>
 * All rows created here are removed in {@link #cleanUp()}.
 */
@EnabledIfSystemProperty(named = "db.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DaoIntegrationTest {

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    private final RoleDao roleDao = new RoleDao();
    private final UserDao userDao = new UserDao();
    private final CategoryDao categoryDao = new CategoryDao();
    private final BrandDao brandDao = new BrandDao();
    private final UnitDao unitDao = new UnitDao();
    private final ProductDao productDao = new ProductDao();
    private final CustomerDao customerDao = new CustomerDao();
    private final SupplierDao supplierDao = new SupplierDao();

    private Category category;
    private Brand brand;
    private Unit unit;
    private final List<Integer> productIds = new ArrayList<>();
    private final List<Integer> customerIds = new ArrayList<>();
    private final List<Integer> supplierIds = new ArrayList<>();
    private Integer userId;

    @BeforeAll
    void createLookups() {
        category = new Category();
        category.setNameAr("مواسير اختبار " + suffix);
        category.setNameEn("Test pipes");
        categoryDao.insert(category);

        brand = new Brand();
        brand.setNameAr("ماركة اختبار " + suffix);
        brand.setCountry("ألمانيا");
        brandDao.insert(brand);

        unit = new Unit();
        unit.setNameAr("متر اختبار " + suffix);
        unit.setAllowsDecimal(true);
        unitDao.insert(unit);
    }

    @AfterAll
    void cleanUp() {
        productIds.forEach(productDao::delete);
        customerIds.forEach(customerDao::delete);
        supplierIds.forEach(supplierDao::delete);
        if (userId != null) {
            new BaseDao() { }.update("DELETE FROM dbo.Users WHERE user_id = ?", userId);
        }
        if (category != null) categoryDao.delete(category.getCategoryId());
        if (brand != null) brandDao.delete(brand.getBrandId());
        if (unit != null) unitDao.delete(unit.getUnitId());
    }

    private Product newProduct(String code) {
        Product p = new Product();
        p.setProductCode(code + "-" + suffix);
        p.setNameAr("ماسورة PPR 20 مم " + code);
        p.setNameEn("PPR pipe 20mm");
        p.setCategoryId(category.getCategoryId());
        p.setBrandId(brand.getBrandId());
        p.setUnitId(unit.getUnitId());
        p.setSize("20mm");
        p.setColor("أخضر");
        p.setPurchasePrice(new BigDecimal("0.375"));
        p.setSalePrice(new BigDecimal("0.650"));
        p.setWholesalePrice(new BigDecimal("0.525"));
        p.setQuantity(new BigDecimal("120.500"));
        p.setMinimumStock(new BigDecimal("25"));
        p.setLocation("رف A-3");
        return p;
    }

    private int insertProduct(Product p) {
        int id = productDao.insert(p);
        productIds.add(id);
        return id;
    }

    @Test
    void seedDataIsPresent() {
        assertTrue(roleDao.findByCode(Role.ADMIN).isPresent());
        assertEquals(5, roleDao.findAll().size());
        assertTrue(customerDao.findCashCustomer().orElseThrow().isCashCustomer());
        assertFalse(unitDao.findAllActive().isEmpty());
        assertFalse(categoryDao.findAllActive().isEmpty());
    }

    @Test
    void productRoundTripKeepsArabicTextAndThreeDecimals() {
        Product p = newProduct("RT");
        p.setBarcode("629" + suffix);
        int id = insertProduct(p);

        Product loaded = productDao.findById(id).orElseThrow();
        assertEquals("ماسورة PPR 20 مم RT", loaded.getNameAr());
        assertEquals(new BigDecimal("0.375"), loaded.getPurchasePrice());
        assertEquals(new BigDecimal("0.650"), loaded.getSalePrice());
        assertEquals(new BigDecimal("0.525"), loaded.getWholesalePrice());
        assertEquals(new BigDecimal("120.500"), loaded.getQuantity());
        assertEquals(category.getNameAr(), loaded.getCategoryName());
        assertEquals(brand.getNameAr(), loaded.getBrandName());
        assertEquals(unit.getNameAr(), loaded.getUnitName());
        assertNullSafeTimestamps(loaded);

        assertEquals(id, productDao.findByBarcode("629" + suffix).orElseThrow().getProductId());
        assertEquals(id, productDao.findByCode("RT-" + suffix).orElseThrow().getProductId());
        assertTrue(productDao.search("PPR 20 مم RT", true).stream().anyMatch(x -> x.getProductId() == id));
    }

    private static void assertNullSafeTimestamps(Product p) {
        assertTrue(p.getCreatedAt() != null && p.getUpdatedAt() != null);
    }

    @Test
    void updateDoesNotOverwriteQuantity() {
        Product p = newProduct("UPD");
        int id = insertProduct(p);

        p.setQuantity(new BigDecimal("999"));          // must be ignored by update()
        p.setSalePrice(new BigDecimal("0.700"));
        p.setBarcode("   ");                            // blank barcode stored as NULL
        productDao.update(p);

        Product loaded = productDao.findById(id).orElseThrow();
        assertEquals(new BigDecimal("120.500"), loaded.getQuantity());
        assertEquals(new BigDecimal("0.700"), loaded.getSalePrice());
        assertNull(loaded.getBarcode());
    }

    @Test
    void productsWithoutBarcodeDoNotConflict() {
        insertProduct(newProduct("NB1"));
        insertProduct(newProduct("NB2"));   // second NULL barcode is allowed by the filtered index
    }

    @Test
    void adjustQuantityCommitsAndRollsBack() {
        int id = insertProduct(newProduct("STK"));

        BigDecimal after = TransactionManager.inTransaction(con ->
                productDao.adjustQuantity(con, id, new BigDecimal("-20.250")));
        assertEquals(new BigDecimal("100.250"), after);

        assertThrows(IllegalStateException.class, () -> TransactionManager.inTransaction(con -> {
            productDao.adjustQuantity(con, id, new BigDecimal("-50"));
            throw new IllegalStateException("simulated failure after stock change");
        }));
        assertEquals(new BigDecimal("100.250"), productDao.findById(id).orElseThrow().getQuantity());
    }

    @Test
    void lowStockIsDetected() {
        Product p = newProduct("LOW");
        p.setQuantity(new BigDecimal("3"));
        int id = insertProduct(p);
        assertTrue(productDao.findLowStock().stream().anyMatch(x -> x.getProductId() == id));
        assertTrue(productDao.countLowStock() >= 1);
    }

    @Test
    void constraintViolationsAreRecognised() {
        insertProduct(newProduct("DUP"));
        DataAccessException duplicate = assertThrows(DataAccessException.class, () -> insertProduct(newProduct("DUP")));
        assertTrue(duplicate.isDuplicateKey());

        Product negative = newProduct("NEG");
        negative.setSalePrice(new BigDecimal("-1"));
        DataAccessException check = assertThrows(DataAccessException.class, () -> insertProduct(negative));
        assertTrue(check.isCheckViolation());

        DataAccessException fk = assertThrows(DataAccessException.class,
                () -> categoryDao.delete(category.getCategoryId()));
        assertTrue(fk.isForeignKeyViolation());
    }

    @Test
    void customerBalanceStartsFromOpeningBalanceAndAdjusts() {
        Customer c = new Customer();
        c.setCustomerCode("C-" + suffix);
        c.setName("مؤسسة الخليج للمقاولات");
        c.setCustomerType(CustomerType.CONTRACTOR);
        c.setPhone("99887766");
        c.setArea("الفروانية");
        c.setCreditLimit(new BigDecimal("1500"));
        c.setOpeningBalance(new BigDecimal("250.125"));
        int id = customerDao.insert(c);
        customerIds.add(id);

        Customer loaded = customerDao.findById(id).orElseThrow();
        assertEquals(CustomerType.CONTRACTOR, loaded.getCustomerType());
        assertEquals(new BigDecimal("250.125"), loaded.getBalance());

        BigDecimal balance = TransactionManager.inTransaction(con ->
                customerDao.adjustBalance(con, id, new BigDecimal("-100.125")));
        assertEquals(new BigDecimal("150.000"), balance);

        loaded.setName("مؤسسة الخليج للمقاولات العامة");
        customerDao.update(loaded);
        Customer updated = customerDao.findById(id).orElseThrow();
        assertEquals("مؤسسة الخليج للمقاولات العامة", updated.getName());
        assertEquals(new BigDecimal("150.000"), updated.getBalance());
        assertTrue(customerDao.search("99887766").stream().anyMatch(x -> x.getCustomerId() == id));
    }

    @Test
    void supplierRoundTrip() {
        Supplier s = new Supplier();
        s.setSupplierCode("S-" + suffix);
        s.setName("شركة الأدوات الصحية المتحدة");
        s.setContactPerson("أبو محمد");
        s.setOpeningBalance(new BigDecimal("1000.500"));
        int id = supplierDao.insert(s);
        supplierIds.add(id);

        BigDecimal balance = TransactionManager.inTransaction(con ->
                supplierDao.adjustBalance(con, id, new BigDecimal("499.500")));
        assertEquals(new BigDecimal("1500.000"), balance);
        assertTrue(supplierDao.search("أبو محمد").stream().anyMatch(x -> x.getSupplierId() == id));
    }

    @Test
    void userWithRole() {
        Role cashier = roleDao.findByCode(Role.CASHIER).orElseThrow();
        User u = new User();
        u.setUsername("cashier_" + suffix);
        u.setPasswordHash("not-a-real-hash");
        u.setFullName("أحمد الكاشير");
        u.setRoleId(cashier.getRoleId());
        userId = userDao.insert(u);

        userDao.updateLastLogin(userId);
        User loaded = userDao.findByUsername("CASHIER_" + suffix).orElseThrow();   // case-insensitive
        assertEquals(Role.CASHIER, loaded.getRoleCode());
        assertEquals("كاشير", loaded.getRoleName());
        assertTrue(loaded.getLastLoginAt() != null);
    }
}
