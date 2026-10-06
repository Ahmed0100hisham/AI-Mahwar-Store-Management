package com.almahwar.service;

import com.almahwar.dao.DatabaseHealthDao;
import com.almahwar.dao.DatabaseHealthDao.Facts;
import com.almahwar.model.DatabaseHealth;
import com.almahwar.model.DatabaseHealth.Status;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link HealthCheckService} for direct SQL Server access. Order: settings → server and login (via {@code master})
 * → database exists and is ONLINE → schema version (older / newer / equal) → critical tables → active admin.
 * The first problem found is reported; technical details go to the application log only.
 */
public class HealthCheckServiceImpl implements HealthCheckService {

    private static final Logger LOG = Logger.getLogger(HealthCheckServiceImpl.class.getName());

    private final Supplier<List<String>> configurationProblems;
    private final DatabaseHealthDao dao;
    private final String databaseName;
    private final String requiredSchema;

    /**
     * @param configurationProblems missing / invalid database settings (Arabic), see {@code AppConfig.databaseProblems}
     */
    public HealthCheckServiceImpl(Supplier<List<String>> configurationProblems, DatabaseHealthDao dao,
                                  String databaseName, String requiredSchema) {
        this.configurationProblems = configurationProblems;
        this.dao = dao;
        this.databaseName = databaseName;
        this.requiredSchema = requiredSchema;
    }

    @Override
    public DatabaseHealth check() {
        DatabaseHealth health = run();
        if (health.status() == Status.HEALTHY) {
            LOG.info("Database health: HEALTHY (schema " + health.schemaVersion() + ")");
        } else {
            LOG.warning("Database health: " + health.status() + " - " + health.details());
        }
        return health;
    }

    private DatabaseHealth run() {
        List<String> problems = configurationProblems.get();
        if (!problems.isEmpty()) {
            return result(Status.CONFIGURATION_ERROR, "إعدادات الاتصال بقاعدة البيانات غير مكتملة.", null, false, problems);
        }
        Optional<String> state;
        try {
            state = dao.databaseState();
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "Database server check failed", e);
            return connectionProblem(e);
        }
        if (state.isEmpty()) {
            return result(Status.DATABASE_UNAVAILABLE, "قاعدة البيانات " + databaseName
                    + " غير موجودة على خادم SQL Server (أو لا يراها حساب الدخول).", null, false, List.of());
        }
        if (!"ONLINE".equals(state.get())) {
            return result(Status.DATABASE_UNAVAILABLE, "قاعدة البيانات " + databaseName + " غير متاحة الآن (الحالة: "
                    + state.get() + ").", null, false, List.of());
        }
        Facts facts;
        try {
            facts = dao.facts();
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "Database check failed", e);
            if (e.getErrorCode() == 4060 || e.getErrorCode() == 916) {
                return result(Status.DATABASE_UNAVAILABLE, "حساب الدخول المضبوط لا يملك صلاحية الوصول إلى قاعدة البيانات "
                        + databaseName + ".", null, false, List.of());
            }
            return connectionProblem(e);
        }
        String schema = facts.schemaVersion();
        if (schema == null) {
            return result(Status.SCHEMA_MISSING, "قاعدة البيانات لا تحتوي على مخطط البرنامج (Schema_Info غير موجود). "
                    + "شغّل سكربت إنشاء قاعدة البيانات أولًا.", null, false, List.of());
        }
        int c;
        try {
            c = SettingsServiceImpl.compareVersions(schema, requiredSchema);
        } catch (RuntimeException unreadable) {
            return result(Status.SCHEMA_MISSING, "إصدار مخطط قاعدة البيانات غير مقروء (" + schema + ").", schema, false,
                    List.of());
        }
        if (c < 0) {
            return result(Status.SCHEMA_OUTDATED, "قاعدة البيانات أقدم من هذا البرنامج (الإصدار " + schema + "، والمطلوب "
                    + requiredSchema + "). خذ نسخة احتياطية ثم شغّل سكربت الترقية database/01_create_database.sql.",
                    schema, false, List.of());
        }
        if (c > 0) {
            return result(Status.SCHEMA_TOO_NEW, "قاعدة البيانات مُرقّاة لإصدار أحدث من هذا البرنامج (الإصدار " + schema
                    + "، وهذا البرنامج يعمل مع " + requiredSchema + "). استخدم إصدار البرنامج الأحدث.", schema, false,
                    List.of());
        }
        if (!facts.missingTables().isEmpty()) {
            return result(Status.CRITICAL_TABLE_MISSING, "جداول أساسية مفقودة في قاعدة البيانات. لا يمكن تشغيل البرنامج عليها.",
                    schema, false, List.of("الجداول المفقودة: " + String.join(", ", facts.missingTables())));
        }
        if (facts.users() > 0 && facts.activeAdmins() == 0) {
            return result(Status.NO_ACTIVE_ADMIN, "لا يوجد مدير نظام فعّال: إدارة المستخدمين والإعدادات والنسخ الاحتياطي "
                    + "غير متاحة حتى يُفعَّل مدير (يلزم تدخل مسؤول قاعدة البيانات).", schema, true, List.of());
        }
        return result(Status.HEALTHY, "قاعدة البيانات متصلة وسليمة.", schema, facts.users() > 0, List.of());
    }

    private DatabaseHealth connectionProblem(SQLException e) {
        Status status = classify(e.getErrorCode(), e.getSQLState(), e.getMessage());
        String message = switch (status) {
            case LOGIN_FAILED -> "رفض خادم SQL Server بيانات الدخول المضبوطة (اسم المستخدم أو كلمة المرور).";
            case CONFIGURATION_ERROR -> "تعذّر إنشاء اتصال مشفّر موثوق بخادم SQL Server: شهادة الخادم غير موثوقة على "
                    + "هذا الجهاز أو لا تطابق اسم الخادم.";
            case SERVER_UNREACHABLE -> "تعذّر الوصول إلى خادم SQL Server. تأكد من تشغيله ومن عنوانه ورقم المنفذ والشبكة.";
            default -> "تعذّر الاتصال بقاعدة البيانات.";
        };
        return result(status, message, null, false, List.of());
    }

    /**
     * What a connection failure means (package-private for unit tests): 18456 = login refused; TLS / certificate
     * errors = configuration; network-level failures (SQL state 08…) = server unreachable.
     */
    static Status classify(int errorCode, String sqlState, String message) {
        String m = message == null ? "" : message;
        if (errorCode == 18456 || m.contains("Login failed")) {
            return Status.LOGIN_FAILED;
        }
        if (m.contains("PKIX") || m.contains("certificate") || m.contains("SSL") || m.contains("TLS")) {
            return Status.CONFIGURATION_ERROR;
        }
        if (errorCode == 4060 || errorCode == 916) {
            return Status.DATABASE_UNAVAILABLE;
        }
        if ((sqlState != null && sqlState.startsWith("08")) || m.contains("connection") || m.contains("timed out")) {
            return Status.SERVER_UNREACHABLE;
        }
        return Status.DATABASE_UNAVAILABLE;
    }

    private DatabaseHealth result(Status status, String message, String schema, boolean usersExist, List<String> details) {
        return new DatabaseHealth(status, message, schema, requiredSchema, usersExist, details);
    }
}
