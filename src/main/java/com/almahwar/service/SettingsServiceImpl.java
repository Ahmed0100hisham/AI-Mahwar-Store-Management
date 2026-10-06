package com.almahwar.service;

import com.almahwar.dao.AuditLogDao;
import com.almahwar.dao.SettingsDao;
import com.almahwar.dao.TransactionManager;
import com.almahwar.model.CompanySettings;
import com.almahwar.model.Permission;
import com.almahwar.model.Settings.About;
import com.almahwar.model.Settings.LogoInfo;
import com.almahwar.model.Settings.Snapshot;
import com.almahwar.model.SystemSettings;
import com.almahwar.util.PhoneNumbers;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static com.almahwar.service.Validation.trimToNull;

/** {@link SettingsService} on SQL Server ({@code System_Settings}, {@code Company_Logo}, {@code Schema_Info}). */
public class SettingsServiceImpl implements SettingsService {

    static final String TABLE = "System_Settings";

    // keys in System_Settings
    static final String K_NAME_AR = "company.name_ar";
    static final String K_NAME_EN = "company.name_en";
    static final String K_PHONE = "company.phone";
    static final String K_PHONE2 = "company.phone2";
    static final String K_EMAIL = "company.email";
    static final String K_ADDRESS = "company.address";
    static final String K_COUNTRY = "company.country";
    static final String K_TAX = "company.tax_number";
    static final String K_CR = "company.cr_number";
    static final String K_VALIDITY = "quotation.validity_days";
    static final String K_TERMS = "quotation.terms";
    static final String K_INVOICE_FOOTER = "invoice.footer";
    static final String K_REPORT_FOOTER = "report.footer";

    /** Used when a key is missing (database not upgraded yet): the same defaults as the schema script. */
    static final Map<String, String> DEFAULTS = Map.of(
            K_NAME_AR, "شركة المحور للأدوات الصحية",
            K_NAME_EN, "Al Mahwar",
            K_ADDRESS, "الكويت",
            K_COUNTRY, "الكويت",
            K_VALIDITY, "14",
            K_INVOICE_FOOTER, "شكراً لتعاملكم معنا");

    /** Arabic names of the keys, for the audit description. */
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry(K_NAME_AR, "الاسم العربي"), Map.entry(K_NAME_EN, "الاسم الإنجليزي"),
            Map.entry(K_PHONE, "الهاتف"), Map.entry(K_PHONE2, "الهاتف الثاني"), Map.entry(K_EMAIL, "البريد"),
            Map.entry(K_ADDRESS, "العنوان"), Map.entry(K_COUNTRY, "الدولة"), Map.entry(K_TAX, "الرقم الضريبي"),
            Map.entry(K_CR, "السجل التجاري"), Map.entry(K_VALIDITY, "مدة صلاحية العرض"),
            Map.entry(K_TERMS, "شروط العرض"), Map.entry(K_INVOICE_FOOTER, "تذييل الفاتورة"),
            Map.entry(K_REPORT_FOOTER, "تذييل التقارير"));

    private final SettingsDao settingsDao;
    private final AuditLogDao auditLogDao;
    private final SecurityContext security;
    private final SystemStatusService systemStatus;
    private final String appName;
    private final String appNameEn;
    private final String appVersion;

    public SettingsServiceImpl(SettingsDao settingsDao, AuditLogDao auditLogDao, SecurityContext security,
                               SystemStatusService systemStatus, String appName, String appNameEn, String appVersion) {
        this.settingsDao = settingsDao;
        this.auditLogDao = auditLogDao;
        this.security = security;
        this.systemStatus = systemStatus;
        this.appName = appName;
        this.appNameEn = appNameEn;
        this.appVersion = appVersion;
    }

    // ======================= Reading =======================

    private void requireLogin() {
        security.currentUser();   // throws when nobody is logged in
    }

    @Override
    public CompanySettings company() {
        requireLogin();
        return company(settingsDao.loadAll());
    }

    @Override
    public SystemSettings system() {
        requireLogin();
        return system(settingsDao.loadAll());
    }

    @Override
    public Optional<byte[]> logo() {
        requireLogin();
        return settingsDao.logo();
    }

    @Override
    public Snapshot load() {
        security.requirePermission(Permission.SETTINGS_VIEW);
        Map<String, String> all = settingsDao.loadAll();
        return new Snapshot(company(all), system(all), settingsDao.logoInfo().orElse(null));
    }

    private static String value(Map<String, String> all, String key) {
        return all.containsKey(key) ? all.get(key) : DEFAULTS.get(key);
    }

    static CompanySettings company(Map<String, String> all) {
        return new CompanySettings(value(all, K_NAME_AR), value(all, K_NAME_EN), value(all, K_PHONE),
                value(all, K_PHONE2), value(all, K_EMAIL), value(all, K_ADDRESS), value(all, K_COUNTRY), CURRENCY_CODE,
                value(all, K_TAX), value(all, K_CR));
    }

    static SystemSettings system(Map<String, String> all) {
        int days;
        try {
            days = Integer.parseInt(value(all, K_VALIDITY));
        } catch (RuntimeException e) {
            days = Integer.parseInt(DEFAULTS.get(K_VALIDITY));
        }
        return new SystemSettings(days, value(all, K_TERMS), value(all, K_INVOICE_FOOTER), value(all, K_REPORT_FOOTER));
    }

    // ======================= Saving =======================

    @Override
    public Snapshot save(CompanySettings company, SystemSettings system) {
        security.requirePermission(Permission.SETTINGS_EDIT);
        int userId = security.currentUser().getUserId();
        CompanySettings c = normalise(company);
        SystemSettings s = clean(system);
        // both parts are checked before anything is written
        Map<String, String> errors = new LinkedHashMap<>();
        collect(errors, validate(c));
        collect(errors, validate(s));
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        TransactionManager.inTransaction(con -> {
            Map<String, String> old = settingsDao.lockAll(con);
            write(con, old, companyValues(c), AuditLogDao.COMPANY_SETTINGS_UPDATED, "تعديل بيانات الشركة", userId);
            write(con, old, systemValues(s), AuditLogDao.SYSTEM_SETTINGS_UPDATED, "تعديل إعدادات النظام", userId);
            return null;
        });
        return load();
    }

    private static void collect(Map<String, String> errors, Validation v) {
        try {
            v.throwIfAny();
        } catch (ValidationException e) {
            errors.putAll(e.getErrors());
        }
    }

    @Override
    public CompanySettings saveCompany(CompanySettings company) {
        security.requirePermission(Permission.SETTINGS_EDIT);
        int userId = security.currentUser().getUserId();
        CompanySettings c = normalise(company);
        validate(c).throwIfAny();
        TransactionManager.inTransaction(con -> {
            write(con, settingsDao.lockAll(con), companyValues(c), AuditLogDao.COMPANY_SETTINGS_UPDATED,
                    "تعديل بيانات الشركة", userId);
            return null;
        });
        return company(settingsDao.loadAll());
    }

    @Override
    public SystemSettings saveSystem(SystemSettings system) {
        security.requirePermission(Permission.SETTINGS_EDIT);
        int userId = security.currentUser().getUserId();
        SystemSettings s = clean(system);
        validate(s).throwIfAny();
        TransactionManager.inTransaction(con -> {
            write(con, settingsDao.lockAll(con), systemValues(s), AuditLogDao.SYSTEM_SETTINGS_UPDATED,
                    "تعديل إعدادات النظام", userId);
            return null;
        });
        return system(settingsDao.loadAll());
    }

    private static SystemSettings clean(SystemSettings s) {
        return new SystemSettings(s.quotationValidityDays(), trimToNull(s.quotationTerms()),
                trimToNull(s.invoiceFooter()), trimToNull(s.reportFooter()));
    }

    private static Map<String, String> companyValues(CompanySettings c) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(K_NAME_AR, c.nameAr());
        values.put(K_NAME_EN, c.nameEn());
        values.put(K_PHONE, c.phone());
        values.put(K_PHONE2, c.phone2());
        values.put(K_EMAIL, c.email());
        values.put(K_ADDRESS, c.address());
        values.put(K_COUNTRY, c.country());
        values.put(K_TAX, c.taxNumber());
        values.put(K_CR, c.crNumber());
        return values;
    }

    private static Map<String, String> systemValues(SystemSettings s) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(K_VALIDITY, String.valueOf(s.quotationValidityDays()));
        values.put(K_TERMS, s.quotationTerms());
        values.put(K_INVOICE_FOOTER, s.invoiceFooter());
        values.put(K_REPORT_FOOTER, s.reportFooter());
        return values;
    }

    /**
     * Inside the caller's transaction (rows already locked by {@code old}): writes the changed values and one audit
     * entry listing them (old / new values). Nothing changed → nothing written.
     */
    private void write(java.sql.Connection con, Map<String, String> old, Map<String, String> values, String action,
                       String verb, int userId) {
        List<String> changed = new ArrayList<>();
        StringBuilder before = new StringBuilder("{");
        StringBuilder after = new StringBuilder("{");
        for (Map.Entry<String, String> e : values.entrySet()) {
            String previous = old.containsKey(e.getKey()) ? old.get(e.getKey()) : DEFAULTS.get(e.getKey());
            if (Objects.equals(previous, e.getValue()) && old.containsKey(e.getKey())) {
                continue;
            }
            settingsDao.put(con, e.getKey(), e.getValue(), userId);
            changed.add(LABELS.getOrDefault(e.getKey(), e.getKey()));
            before.append(before.length() > 1 ? "," : "").append(json(e.getKey())).append(':').append(json(previous));
            after.append(after.length() > 1 ? "," : "").append(json(e.getKey())).append(':').append(json(e.getValue()));
        }
        if (!changed.isEmpty()) {
            auditLogDao.log(con, userId, action, TABLE, action, before.append('}').toString(),
                    after.append('}').toString(), verb + ": " + String.join("، ", changed));
        }
    }

    private static String json(String text) {
        return text == null ? "null" : "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "") + "\"";
    }

    static CompanySettings normalise(CompanySettings c) {
        return new CompanySettings(trimToNull(c.nameAr()), trimToNull(c.nameEn()), PhoneNumbers.normalize(c.phone()),
                PhoneNumbers.normalize(c.phone2()), trimToNull(c.email()), trimToNull(c.address()),
                trimToNull(c.country()), c.currencyCode() == null ? CURRENCY_CODE : c.currencyCode().trim(),
                trimToNull(c.taxNumber()), trimToNull(c.crNumber()));
    }

    /** Package-private for unit tests. */
    static Validation validate(CompanySettings c) {
        Validation v = new Validation();
        v.required(NAME_AR, c.nameAr(), "اسم الشركة بالعربي مطلوب.");
        v.maxLength(NAME_AR, c.nameAr(), 150, "اسم الشركة");
        v.maxLength(NAME_EN, c.nameEn(), 150, "الاسم الإنجليزي");
        if (c.phone() != null) {
            PartyRules.phone(v, PHONE, c.phone());
        }
        if (c.phone2() != null) {
            PartyRules.phone(v, PHONE2, c.phone2());
        }
        PartyRules.email(v, EMAIL, c.email());
        v.maxLength(ADDRESS, c.address(), 300, "العنوان");
        v.maxLength(COUNTRY, c.country(), 100, "الدولة");
        v.maxLength(TAX_NUMBER, c.taxNumber(), 50, "الرقم الضريبي");
        v.maxLength(CR_NUMBER, c.crNumber(), 50, "السجل التجاري");
        if (!CURRENCY_CODE.equals(c.currencyCode())) {
            v.error(CURRENCY, "العملة ثابتة (KWD بثلاث منازل عشرية) ولا يمكن تغييرها من الإعدادات.");
        }
        return v;
    }

    /** Package-private for unit tests. */
    static Validation validate(SystemSettings s) {
        Validation v = new Validation();
        if (s.quotationValidityDays() < MIN_VALIDITY_DAYS || s.quotationValidityDays() > MAX_VALIDITY_DAYS) {
            v.error(VALIDITY_DAYS, "مدة صلاحية العرض يجب أن تكون بين " + MIN_VALIDITY_DAYS + " و " + MAX_VALIDITY_DAYS
                    + " يومًا.");
        }
        // the default terms are copied into new quotations (Quotations.terms holds 1000 characters)
        v.maxLength(QUOTATION_TERMS, s.quotationTerms(), 1000, "شروط العرض");
        v.maxLength(INVOICE_FOOTER, s.invoiceFooter(), 300, "تذييل الفاتورة");
        v.maxLength(REPORT_FOOTER, s.reportFooter(), 300, "تذييل التقارير");
        return v;
    }

    // ======================= Logo =======================

    /** What a checked image is. */
    record ImageCheck(String contentType, int width, int height) {
    }

    /**
     * Checks a logo file: size, PNG / JPEG signature, a readable image of sensible dimensions. Package-private
     * for unit tests.
     */
    static ImageCheck checkImage(byte[] content) {
        if (content == null || content.length == 0) {
            throw new ValidationException(LOGO, "الملف فارغ.");
        }
        if (content.length > MAX_LOGO_BYTES) {
            throw new ValidationException(LOGO, "حجم الشعار يجب ألا يزيد عن 1 ميجابايت.");
        }
        String type;
        if (content.length > 8 && (content[0] & 0xFF) == 0x89 && content[1] == 'P' && content[2] == 'N' && content[3] == 'G') {
            type = "image/png";
        } else if (content.length > 3 && (content[0] & 0xFF) == 0xFF && (content[1] & 0xFF) == 0xD8
                && (content[2] & 0xFF) == 0xFF) {
            type = "image/jpeg";
        } else {
            throw new ValidationException(LOGO, "الشعار يجب أن يكون صورة PNG أو JPG.");
        }
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new ValidationException(LOGO, "تعذّرت قراءة الصورة؛ الملف تالف أو ليس صورة صحيحة.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (w < MIN_LOGO_SIDE || h < MIN_LOGO_SIDE || w > MAX_LOGO_SIDE || h > MAX_LOGO_SIDE) {
                    throw new ValidationException(LOGO, "أبعاد الشعار يجب أن تكون بين " + MIN_LOGO_SIDE + " و "
                            + MAX_LOGO_SIDE + " بكسل (الحالية " + w + "×" + h + ").");
                }
                reader.read(0);   // decodes the whole image: a truncated / corrupted file fails here
                return new ImageCheck(type, w, h);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof ValidationException ve) {
                throw ve;
            }
            throw new ValidationException(LOGO, "تعذّرت قراءة الصورة؛ الملف تالف أو ليس صورة صحيحة.");
        }
    }

    @Override
    public LogoInfo changeLogo(String fileName, byte[] content) {
        security.requirePermission(Permission.SETTINGS_EDIT);
        int userId = security.currentUser().getUserId();
        ImageCheck img = checkImage(content);
        String name = fileName == null ? null : fileName.replaceAll(".*[/\\\\]", "");   // display name only
        if (name != null && name.length() > 200) {
            name = name.substring(name.length() - 200);
        }
        String shown = name;
        String hash = sha256(content);
        TransactionManager.inTransaction(con -> {
            settingsDao.saveLogo(con, content, img.contentType(), shown, img.width(), img.height(), hash, userId);
            auditLogDao.log(con, userId, AuditLogDao.LOGO_CHANGED, "Company_Logo", "1",
                    "تغيير شعار الشركة: " + (shown == null ? "" : shown + " ") + "(" + img.width() + "×" + img.height()
                            + "، " + (content.length / 1024) + " ك.ب، " + img.contentType() + ")");
            return null;
        });
        return settingsDao.logoInfo().orElseThrow();
    }

    @Override
    public void removeLogo() {
        security.requirePermission(Permission.SETTINGS_EDIT);
        int userId = security.currentUser().getUserId();
        TransactionManager.inTransaction(con -> {
            if (settingsDao.deleteLogo(con)) {
                auditLogDao.log(con, userId, AuditLogDao.LOGO_CHANGED, "Company_Logo", "1", "إزالة شعار الشركة");
            }
            return null;
        });
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ======================= About =======================

    @Override
    public About about() {
        security.requirePermission(Permission.SETTINGS_VIEW);
        boolean connected = systemStatus.isBackendReachable();
        String server = null;
        String schema = null;
        if (connected) {
            try {
                server = settingsDao.serverVersion();
                schema = settingsDao.schemaVersion().orElse(null);
            } catch (RuntimeException e) {
                connected = false;
            }
        }
        return new About(appName, appNameEn, appVersion, System.getProperty("java.version"), connected,
                systemStatus.backendDescription(), server, schema, REQUIRED_SCHEMA_VERSION,
                schema != null && compareVersions(schema, REQUIRED_SCHEMA_VERSION) >= 0);
    }

    /** Compares dotted versions numerically ("1.10.0" > "1.9.0"). */
    static int compareVersions(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? parse(x[i]) : 0;
            int q = i < y.length ? parse(y[i]) : 0;
            if (p != q) {
                return Integer.compare(p, q);
            }
        }
        return 0;
    }

    private static int parse(String part) {
        try {
            return Integer.parseInt(part.replaceAll("[^0-9].*$", "").isEmpty() ? "0" : part.replaceAll("[^0-9].*$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
