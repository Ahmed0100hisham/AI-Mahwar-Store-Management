package com.almahwar.service;

import com.almahwar.config.AppConfig;
import com.almahwar.dao.AuditLogDao;
import com.almahwar.dao.BaseDao;
import com.almahwar.dao.CategoryDao;
import com.almahwar.dao.CustomerDao;
import com.almahwar.dao.ProductDao;
import com.almahwar.dao.QuotationDao;
import com.almahwar.dao.RoleDao;
import com.almahwar.dao.SaleDao;
import com.almahwar.dao.SettingsDao;
import com.almahwar.dao.UnitDao;
import com.almahwar.dao.UserDao;
import com.almahwar.model.Category;
import com.almahwar.model.CompanySettings;
import com.almahwar.model.Customer;
import com.almahwar.model.Product;
import com.almahwar.model.Quotation;
import com.almahwar.model.QuotationItem;
import com.almahwar.model.Role;
import com.almahwar.model.SaleType;
import com.almahwar.model.Settings.About;
import com.almahwar.model.Settings.LogoInfo;
import com.almahwar.model.Settings.Snapshot;
import com.almahwar.model.SystemSettings;
import com.almahwar.model.Unit;
import com.almahwar.model.User;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Settings against a real SQL Server: defaults, persistence, validation, permissions, logo, audit, atomic save,
 * quotation defaults vs. historical quotations, about, and that settings never touch business data.
 * The original settings and logo are put back afterwards. Enable with {@code -Ddb.it=true}.
 */
@EnabledIfSystemProperty(named = "db.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SettingsIntegrationTest {

    private final String suffix = UUID.randomUUID().toString().substring(0, 6);
    private final TestSecurity security = new TestSecurity();
    private final SettingsDao settingsDao = new SettingsDao();
    private final AppConfig cfg = AppConfig.getInstance();
    private final SettingsService settings = service(settingsDao);

    private int adminId;
    private int cashierId;
    private int accountantId;
    private int storekeeperId;
    private Map<String, String> originalSettings;
    private Optional<byte[]> originalLogo;
    private Optional<LogoInfo> originalLogoInfo;
    private Integer categoryId;
    private Integer unitId;
    private Customer customer;
    private Product product;

    private final class Sql extends BaseDao {
        int exec(String sql, Object... params) {
            return update(sql, params);
        }

        long count(String sql, Object... params) {
            return queryLong(sql, params);
        }

        String text(String sql, Object... params) {
            return queryOne(sql, rs -> rs.getString(1), params).orElse(null);
        }
    }

    private final Sql sql = new Sql();

    private SettingsService service(SettingsDao dao) {
        return new SettingsServiceImpl(dao, new AuditLogDao(), security, new SystemStatusServiceImpl(cfg), cfg.appName(),
                cfg.appNameEn(), cfg.appVersion());
    }

    @BeforeAll
    void setUp() {
        adminId = newUser(Role.ADMIN);
        cashierId = newUser(Role.CASHIER);
        accountantId = newUser(Role.ACCOUNTANT);
        storekeeperId = newUser(Role.STOREKEEPER);
        originalSettings = settingsDao.loadAll();
        originalLogo = settingsDao.logo();
        originalLogoInfo = settingsDao.logoInfo();
        Category c = new Category();
        c.setNameAr("قسم إعدادات " + suffix);
        categoryId = new CategoryDao().insert(c);
        Unit u = new Unit();
        u.setNameAr("حبة إعدادات " + suffix);
        unitId = new UnitDao().insert(u);
        customer = new Customer();
        customer.setCustomerCode("SET-" + suffix);
        customer.setName("عميل إعدادات " + suffix);
        customer.setCreditLimit(BigDecimal.ZERO);
        customer.setCustomerId(new CustomerDao().insert(customer));
        product = new Product();
        product.setProductCode("SETP-" + suffix);
        product.setNameAr("صنف إعدادات " + suffix);
        product.setCategoryId(categoryId);
        product.setUnitId(unitId);
        product.setPurchasePrice(new BigDecimal("1.000"));
        product.setSalePrice(new BigDecimal("2.000"));
        product.setQuantity(BigDecimal.ZERO);
        new ProductDao().insert(product);
    }

    private int newUser(String role) {
        User u = new User();
        u.setUsername("set_" + role.toLowerCase().substring(0, 3) + "_" + suffix);
        u.setPasswordHash("pbkdf2_sha256$1$x$y");
        u.setFullName("مستخدم إعدادات " + role);
        u.setRoleId(new RoleDao().findByCode(role).orElseThrow().getRoleId());
        return new UserDao().insert(u);
    }

    @BeforeEach
    void asAdmin() {
        security.admin(adminId);
    }

    @AfterAll
    void restore() {
        // put the original values back exactly (also NULLs), and the original logo
        for (Map.Entry<String, String> e : originalSettings.entrySet()) {
            sql.exec("UPDATE dbo.System_Settings SET setting_value = ?, updated_by = NULL WHERE setting_key = ?",
                    e.getValue(), e.getKey());
        }
        sql.exec("DELETE FROM dbo.System_Settings WHERE setting_key NOT IN (" + String.join(",",
                originalSettings.keySet().stream().map(k -> "'" + k + "'").toList()) + ")");
        if (originalLogo.isPresent()) {
            LogoInfo i = originalLogoInfo.orElseThrow();
            com.almahwar.dao.TransactionManager.inTransaction(con -> {
                settingsDao.saveLogo(con, originalLogo.get(), i.contentType(), i.fileName(), i.width(), i.height(),
                        "0".repeat(64), adminId);
                return null;
            });
            sql.exec("UPDATE dbo.Company_Logo SET updated_by = NULL");
        } else {
            sql.exec("DELETE FROM dbo.Company_Logo");
        }
        Object[] users = {adminId, cashierId, accountantId, storekeeperId};
        sql.exec("DELETE FROM dbo.Quotations WHERE user_id IN (?, ?, ?, ?)", users);
        sql.exec("DELETE FROM dbo.Products WHERE product_id = ?", product.getProductId());
        sql.exec("DELETE FROM dbo.Customers WHERE customer_id = ?", customer.getCustomerId());
        sql.exec("DELETE FROM dbo.Categories WHERE category_id = ?", categoryId);
        sql.exec("DELETE FROM dbo.Units WHERE unit_id = ?", unitId);
        sql.exec("DELETE FROM dbo.Audit_Log WHERE user_id IN (?, ?, ?, ?)", users);
        sql.exec("DELETE FROM dbo.Users WHERE user_id IN (?, ?, ?, ?)", users);
    }

    private static CompanySettings edited(CompanySettings c, String nameAr, String phone, String email) {
        return new CompanySettings(nameAr, c.nameEn(), phone, c.phone2(), email, c.address(), c.country(),
                c.currencyCode(), c.taxNumber(), c.crNumber());
    }

    private long audits(String action) {
        return sql.count("SELECT COUNT(*) FROM dbo.Audit_Log WHERE action = ? AND user_id = ?", action, adminId);
    }

    // ======================= reading, defaults, persistence =======================

    @Test
    void defaultsAndPersistence() {
        Snapshot s = settings.load();
        assertTrue(s.company().nameAr() != null && !s.company().nameAr().isBlank(), "company name");
        assertEquals("KWD", s.company().currencyCode());
        assertTrue(s.system().quotationValidityDays() >= 1);

        String name = "شركة المحور التجريبية " + suffix;
        CompanySettings c = edited(s.company(), name, "22223333", "info@almahwar.com");
        SystemSettings sys = new SystemSettings(21, "الأسعار شاملة التوصيل " + suffix, "شكرًا " + suffix, "تذييل " + suffix);
        long companyAudits = audits("COMPANY_SETTINGS_UPDATED");
        long systemAudits = audits("SYSTEM_SETTINGS_UPDATED");
        settings.save(c, sys);

        // a new service instance (a new program start) reads the same values from the database
        Snapshot again = service(new SettingsDao()).load();
        assertEquals(name, again.company().nameAr());
        assertEquals(com.almahwar.util.PhoneNumbers.normalize("22223333"), again.company().phone(),
                "phone normalised like the customers' phones");
        assertEquals("info@almahwar.com", again.company().email());
        assertEquals(21, again.system().quotationValidityDays());
        assertEquals("الأسعار شاملة التوصيل " + suffix, again.system().quotationTerms());
        assertEquals("تذييل " + suffix, again.system().reportFooter());
        assertEquals(companyAudits + 1, audits("COMPANY_SETTINGS_UPDATED"));
        assertEquals(systemAudits + 1, audits("SYSTEM_SETTINGS_UPDATED"));
        String description = sql.text("SELECT TOP 1 description FROM dbo.Audit_Log WHERE action = 'COMPANY_SETTINGS_UPDATED'"
                + " AND user_id = ? ORDER BY log_id DESC", adminId);
        assertTrue(description.contains("الاسم العربي") && description.contains("الهاتف"), description);

        // saving the same values again writes nothing
        settings.save(again.company(), again.system());
        assertEquals(companyAudits + 1, audits("COMPANY_SETTINGS_UPDATED"), "no change → no audit");

        // the print readers (any logged-in user) see the new profile at once
        security.as(Role.CASHIER, cashierId);
        assertEquals(name, settings.company().nameAr());
        assertEquals("شكرًا " + suffix, settings.system().invoiceFooter());
    }

    @Test
    void validationRejectsAndChangesNothing() {
        Snapshot before = settings.load();
        ValidationException e = assertThrows(ValidationException.class, () -> settings.save(
                edited(before.company(), "شركة " + suffix, null, "bad-email"), new SystemSettings(0, null, null, null)));
        assertTrue(e.getErrors().containsKey(SettingsService.EMAIL), "both tabs reported");
        assertTrue(e.getErrors().containsKey(SettingsService.VALIDITY_DAYS));
        assertEquals(before, settings.load(), "nothing saved");
        assertThrows(ValidationException.class, () -> settings.save(edited(before.company(), " ", null, null), before.system()));
        assertThrows(ValidationException.class, () -> settings.save(before.company().withCurrency("USD"), before.system()),
                "the currency cannot change");
        assertThrows(ValidationException.class, () -> settings.saveSystem(new SystemSettings(366, null, null, null)));
        assertEquals(before, settings.load());
    }

    @Test
    void saveIsAtomic() {
        Snapshot before = settings.load();
        long auditsBefore = sql.count("SELECT COUNT(*) FROM dbo.Audit_Log");
        // the third write fails: the two before it must be rolled back
        SettingsDao failing = new SettingsDao() {
            private int writes;

            @Override
            public void put(Connection con, String key, String value, int userId) {
                if (++writes == 3) {
                    throw new IllegalStateException("disk full (test)");
                }
                super.put(con, key, value, userId);
            }
        };
        SettingsService broken = service(failing);
        CompanySettings c = new CompanySettings("ذرّية " + suffix, "Atomic " + suffix, "99887766", null, "a@b.co",
                "عنوان " + suffix, "الكويت", "KWD", null, null);
        assertThrows(IllegalStateException.class, () -> broken.save(c, new SystemSettings(30, "x", "y", "z")));
        assertEquals(before, settings.load(), "rolled back: no half-updated profile");
        assertEquals(auditsBefore, sql.count("SELECT COUNT(*) FROM dbo.Audit_Log"), "no audit either");
    }

    // ======================= permissions =======================

    @Test
    void permissions() {
        Snapshot s = settings.load();
        for (int[] role : new int[][]{{0, cashierId}, {1, accountantId}, {2, storekeeperId}}) {
            String code = role[0] == 0 ? Role.CASHIER : role[0] == 1 ? Role.ACCOUNTANT : Role.STOREKEEPER;
            security.as(code, role[1]);
            assertThrows(AccessDeniedException.class, settings::load, code + ": view");
            assertThrows(AccessDeniedException.class, settings::about, code + ": about");
            assertThrows(AccessDeniedException.class, () -> settings.save(s.company(), s.system()), code + ": save");
            assertThrows(AccessDeniedException.class, () -> settings.saveCompany(s.company()), code);
            assertThrows(AccessDeniedException.class, () -> settings.changeLogo("x.png", SettingsRulesTest.image("png", 50, 50)), code);
            assertThrows(AccessDeniedException.class, settings::removeLogo, code);
            settings.company();   // printing still works for every logged-in user
        }
        security.logout();
        assertThrows(RuntimeException.class, settings::company, "not without a session");
        assertThrows(RuntimeException.class, settings::load);
    }

    // ======================= logo =======================

    @Test
    void logoChangeAndRemove() {
        byte[] png = SettingsRulesTest.image("png", 240, 96);
        LogoInfo info = settings.changeLogo("C:\\Users\\someone\\Desktop\\logo.png", png);
        assertEquals("logo.png", info.fileName(), "only the name is kept, never a path");
        assertEquals(240, info.width());
        assertEquals(96, info.height());
        assertEquals(png.length, info.sizeBytes());
        assertArrayEquals(png, settings.logo().orElseThrow(), "stored in the database, same bytes");
        assertEquals(info, settings.load().logo());
        long logoAudits = audits("LOGO_CHANGED");

        assertThrows(ValidationException.class, () -> settings.changeLogo("bad.png", "not an image".getBytes()));
        assertArrayEquals(png, settings.logo().orElseThrow(), "an invalid file keeps the previous logo");
        assertEquals(logoAudits, audits("LOGO_CHANGED"));

        byte[] jpg = SettingsRulesTest.image("jpg", 120, 120);
        assertEquals("image/jpeg", settings.changeLogo("logo.jpg", jpg).contentType());
        settings.removeLogo();
        assertTrue(settings.logo().isEmpty());
        assertNull(settings.load().logo());
        assertEquals(logoAudits + 2, audits("LOGO_CHANGED"));
    }

    // ======================= quotations =======================

    @Test
    void quotationDefaultsDoNotChangeHistoricalQuotations() {
        Snapshot s = settings.load();
        settings.saveSystem(new SystemSettings(10, "شروط أولى " + suffix, s.system().invoiceFooter(), s.system().reportFooter()));
        SystemSettings d = settings.system();
        // what the quotation form does for a new quotation
        Quotation q = new Quotation();
        q.setCustomerId(customer.getCustomerId());
        q.setPriceType(SaleType.RETAIL);
        q.setValidUntil(LocalDate.now().plusDays(d.quotationValidityDays()));
        q.setTerms(d.quotationTerms());
        QuotationItem it = new QuotationItem();
        it.setProductId(product.getProductId());
        it.setQuantity(BigDecimal.ONE);
        it.setUnitPrice(new BigDecimal("2.000"));
        q.setItems(new ArrayList<>(List.of(it)));
        q.setRequestId(UUID.randomUUID());
        QuotationService quotations = new QuotationServiceImpl(new QuotationDao(), new CustomerDao(), new ProductDao(),
                new UnitDao(), new SaleDao(), null, new AuditLogDao(), security);
        Quotation saved = quotations.save(q);
        assertEquals(LocalDate.now().plusDays(10), saved.getValidUntil());
        assertEquals("شروط أولى " + suffix, saved.getTerms());

        settings.saveSystem(new SystemSettings(30, "شروط ثانية " + suffix, d.invoiceFooter(), d.reportFooter()));
        Quotation old = quotations.findById(saved.getQuotationId()).orElseThrow();
        assertEquals("شروط أولى " + suffix, old.getTerms(), "the saved quotation keeps its own terms");
        assertEquals(LocalDate.now().plusDays(10), old.getValidUntil(), "and its validity");
        assertEquals("شروط ثانية " + suffix, settings.system().quotationTerms());
    }

    // ======================= about =======================

    @Test
    void aboutShowsVersionsButNoSecrets() {
        About a = settings.about();
        assertTrue(a.databaseConnected());
        assertEquals(SettingsService.REQUIRED_SCHEMA_VERSION, a.requiredSchemaVersion());
        assertTrue(a.schemaVersion() != null && a.schemaCompatible(), "schema " + a.schemaVersion());
        assertTrue(a.sqlServerVersion() != null && !a.sqlServerVersion().isBlank());
        assertFalse(a.appVersion().isBlank());
        assertTrue(a.appVersion().equals(cfg.appVersion()), "one version source");
        String all = a.toString();
        String password = cfg.get("db.password", "");
        assertFalse(!password.isBlank() && all.contains(password), "no password");
        assertFalse(all.toLowerCase().contains("password") || all.toLowerCase().contains("jdbc:"), "no connection secrets");
    }

    // ======================= business data untouched =======================

    private Map<String, String> snapshot() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String table : List.of("Products", "Stock_Movements", "Sales", "Sale_Items", "Purchases", "Purchase_Items",
                "Sale_Returns", "Purchase_Returns", "Account_Ledger", "Cash_Transactions", "Expenses", "Customers",
                "Suppliers", "Customer_Payments", "Supplier_Payments")) {
            m.put(table, sql.text("SELECT CAST(COUNT(*) AS varchar(20)) + ':' + CAST(COALESCE(CHECKSUM_AGG(BINARY_CHECKSUM(*)), 0)"
                    + " AS varchar(20)) FROM dbo." + table));
        }
        return m;
    }

    @Test
    void settingsNeverTouchBusinessData() {
        Map<String, String> before = snapshot();
        Snapshot s = settings.load();
        settings.save(edited(s.company(), "شركة اختبار مالي " + suffix, "22220000", null),
                new SystemSettings(45, "شروط " + suffix, "تذييل " + suffix, null));
        settings.changeLogo("l.png", SettingsRulesTest.image("png", 64, 64));
        settings.removeLogo();
        settings.about();
        assertEquals(before, snapshot(), "stock, documents, ledger, cash, balances unchanged");
    }
}
