package com.almahwar.service;

import com.almahwar.dao.AuditLogDao;
import com.almahwar.dao.BackupHistoryDao;
import com.almahwar.dao.DataAccessException;
import com.almahwar.dao.DatabaseBackupDao;
import com.almahwar.dao.DatabaseBackupDao.BackupHeader;
import com.almahwar.dao.DatabaseBackupDao.DatabaseFile;
import com.almahwar.dao.DatabaseBackupDao.DatabaseState;
import com.almahwar.dao.DatabaseBackupDao.Health;
import com.almahwar.model.BackupInfo;
import com.almahwar.model.BackupInfo.Kind;
import com.almahwar.model.BackupInfo.Verification;
import com.almahwar.model.BackupResult;
import com.almahwar.model.BackupSettings;
import com.almahwar.model.BackupVerificationResult;
import com.almahwar.model.Permission;
import com.almahwar.model.RestoreResult;
import com.almahwar.model.UserSession;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Backup / verify / restore through SQL Server itself (see {@link BackupRestoreService}).
 * <p>
 * Concurrency: one operation at a time on this PC (a lock that is never waited for) and across all PCs (SQL
 * Server's application lock, {@link DatabaseBackupDao#tryLock()}); a second request is refused at once.
 * <p>
 * Audit after a restore: the restored database is as old as its backup, so entries written before the restore
 * (RESTORE_REQUESTED, PRE_RESTORE_BACKUP_CREATED) and the backup history are written again into it afterwards,
 * together with RESTORE_COMPLETED; SQL Server also keeps its own record in {@code msdb.dbo.restorehistory}, and
 * the application log has every step.
 */
public class BackupRestoreServiceImpl implements BackupRestoreService {

    private static final Logger LOG = Logger.getLogger(BackupRestoreServiceImpl.class.getName());
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    static final String BUSY = "توجد عملية نسخ احتياطي أو استعادة قيد التنفيذ الآن (على هذا الجهاز أو جهاز آخر). انتظر حتى تنتهي ثم حاول مرة أخرى.";
    static final String NO_SERVER = "تعذّر الاتصال بخادم SQL Server.";

    /**
     * Non-secret settings ({@code backup.*}, {@code db.name}; usual {@code AppConfig} precedence).
     *
     * @param serverDirectory the backup folder as SQL Server sees it; blank = SQL Server's default backup folder
     * @param filePrefix      file name prefix; blank = the database name
     */
    public record Config(boolean enabled, String serverDirectory, String filePrefix, String databaseName,
                         String requiredSchemaVersion) {
    }

    /** Who acts (for the history and the audit). */
    private record Actor(Integer userId, String name) {
    }

    /** An acquired operation lock. */
    private interface Held extends AutoCloseable {
        @Override
        void close();
    }

    private final Config config;
    private final DatabaseBackupDao backups;
    private final BackupHistoryDao history;
    private final SecurityContext security;
    private final Runnable endSession;
    private final ReentrantLock running = new ReentrantLock();

    /**
     * @param endSession ends the current session after the database was replaced (the desktop: log out)
     */
    public BackupRestoreServiceImpl(Config config, DatabaseBackupDao backups, BackupHistoryDao history,
                                    SecurityContext security, Runnable endSession) {
        BackupPolicy.databaseName(config.databaseName());
        this.config = config;
        this.backups = backups;
        this.history = history;
        this.security = security;
        this.endSession = endSession;
    }

    @Override
    public String databaseName() {
        return config.databaseName();
    }

    // ======================= settings / history =======================

    @Override
    public BackupSettings settings() {
        security.requirePermission(Permission.BACKUP_VIEW);
        return resolveSettings();
    }

    private BackupSettings resolveSettings() {
        String db = config.databaseName();
        if (!config.enabled()) {
            return new BackupSettings(false, db, null, false,
                    "النسخ الاحتياطي معطّل في إعدادات البرنامج (backup.enabled=false).");
        }
        String configured = config.serverDirectory();
        boolean fromDefault = configured == null || configured.isBlank();
        String directory = configured;
        if (fromDefault) {
            try {
                directory = backups.serverDefaultDirectory().orElse(null);
            } catch (DataAccessException e) {
                LOG.log(Level.WARNING, "Could not read SQL Server's default backup folder", e);
                return new BackupSettings(true, db, null, true, NO_SERVER);
            }
            if (directory == null) {
                return new BackupSettings(true, db, null, true,
                        "حدّد مجلد النسخ الاحتياطي على جهاز خادم SQL Server في الإعداد backup.server-directory.");
            }
        }
        try {
            directory = BackupPolicy.directory(directory);
            BackupPolicy.prefix(config.filePrefix(), db);
        } catch (IllegalArgumentException e) {
            return new BackupSettings(true, db, configured, fromDefault, e.getMessage());
        }
        return new BackupSettings(true, db, directory, fromDefault, null);
    }

    private String readyDirectory() {
        BackupSettings s = resolveSettings();
        if (!s.ready()) {
            throw new BackupException(s.problem());
        }
        return s.serverDirectory();
    }

    @Override
    public List<BackupInfo> history() {
        security.requirePermission(Permission.BACKUP_VIEW);
        return history.findAll();
    }

    // ======================= create =======================

    @Override
    public BackupResult createBackup() {
        security.requirePermission(Permission.BACKUP_CREATE);
        Actor actor = actor();
        String directory = readyDirectory();
        try (Held ignored = lock()) {
            BackupInfo created = create(Kind.MANUAL, directory, actor);
            BackupVerificationResult verification = security.hasPermission(Permission.BACKUP_VERIFY)
                    ? verifyRow(created, actor) : null;
            return new BackupResult(history.findById(created.backupId()).orElse(created), verification);
        }
    }

    /**
     * Reserves a new file name (never an existing file: SQL Server is asked first, and the history's UNIQUE file
     * name settles a race between PCs), lets SQL Server write the backup, then checks the file holds exactly this
     * one full backup. The row ends COMPLETED or FAILED — never "completed" when SQL Server failed.
     */
    private BackupInfo create(Kind kind, String directory, Actor actor) {
        String db = config.databaseName();
        String prefix = BackupPolicy.prefix(config.filePrefix(), db);
        LocalDateTime now;
        String schema;
        try {
            now = backups.serverTime();
            schema = backups.schemaVersion().orElse(null);
        } catch (DataAccessException e) {
            LOG.log(Level.WARNING, "Backup preparation failed", e);
            throw new BackupException(NO_SERVER, e);
        }
        int id = -1;
        String name = null;
        String path = null;
        for (int attempt = 0; attempt < 50 && id < 0; attempt++) {
            String candidate = BackupPolicy.fileName(kind, prefix, now, attempt);
            String candidatePath = BackupPolicy.serverPath(directory, candidate);
            try {
                if (backups.headers(candidatePath).isPresent()) {
                    continue;   // a backup file of that name exists: never overwrite or append
                }
            } catch (DataAccessException e) {
                if (permissionDenied(e)) {
                    // the login may not read backup files at all: say so (it is not a name collision)
                    LOG.log(Level.WARNING, "Backup file check refused by SQL Server", e);
                    throw new BackupException("فشل إنشاء النسخة الاحتياطية: " + describe(e), e);
                }
                continue;   // something that is not a backup has that name: also taken
            }
            try {
                id = history.insertCreating(candidate, directory, kind, schema, actor.userId(), actor.name());
                name = candidate;
                path = candidatePath;
            } catch (DataAccessException e) {
                if (!e.isDuplicateKey()) {
                    LOG.log(Level.WARNING, "Backup reservation failed", e);
                    throw new BackupException(NO_SERVER, e);
                }
            }
        }
        if (id < 0) {
            throw new BackupException("تعذّر اختيار اسم جديد لملف النسخة الاحتياطية.");
        }
        audit(actor, AuditLogDao.BACKUP_STARTED, id, (kind == Kind.PRE_RESTORE
                ? "بدء نسخة احتياطية وقائية قبل الاستعادة: " : "بدء نسخة احتياطية: ") + name);
        try {
            backups.backup(path, "AlMahwar " + kind.name() + " " + name);
        } catch (DataAccessException e) {
            String reason = describe(e);
            LOG.log(Level.WARNING, "Backup failed: " + name, e);
            failRow(id, reason);
            audit(actor, AuditLogDao.BACKUP_FAILED, id, name + ": " + reason);
            throw new BackupException("فشل إنشاء النسخة الاحتياطية: " + reason, e);
        }
        List<BackupHeader> sets;
        try {
            sets = backups.headers(path).orElse(List.of());
        } catch (DataAccessException e) {
            LOG.log(Level.WARNING, "Backup header unreadable: " + name, e);
            sets = List.of();
        }
        String problem = sets.size() != 1
                ? "لم يُعثر على النسخة في الملف كما هو متوقع (" + sets.size() + " نسخ في الملف)."
                : !sets.get(0).fullDatabase() || !db.equalsIgnoreCase(sets.get(0).databaseName())
                ? "محتوى الملف لا يطابق النسخة المطلوبة." : null;
        if (problem != null) {
            failRow(id, problem);
            audit(actor, AuditLogDao.BACKUP_FAILED, id, name + ": " + problem);
            throw new BackupException("فشل إنشاء النسخة الاحتياطية: " + problem);
        }
        long size = sets.get(0).sizeBytes();
        history.markCompleted(id, size);
        audit(actor, AuditLogDao.BACKUP_COMPLETED, id, name + " (" + size(size) + ")");
        return history.findById(id).orElseThrow();
    }

    private void failRow(int id, String reason) {
        try {
            history.markFailed(id, reason);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not mark backup " + id + " as failed", e);
        }
    }

    // ======================= verify =======================

    @Override
    public BackupVerificationResult verify(int backupId) {
        security.requirePermission(Permission.BACKUP_VERIFY);
        Actor actor = actor();
        BackupInfo backup = find(backupId);
        String unusable = BackupPolicy.unusableReason(backup);
        if (unusable != null) {
            throw new ValidationException("backup", unusable);
        }
        try (Held ignored = lock()) {
            return verifyRow(backup, actor);
        }
    }

    /** VERIFYONLY plus the header checks; the outcome is saved in the history and the audit log. */
    private BackupVerificationResult verifyRow(BackupInfo backup, Actor actor) {
        String problem = null;
        boolean refused = false;
        BackupHeader header = null;
        try {
            String path = BackupPolicy.serverPath(backup.serverDirectory(), backup.fileName());
            Optional<List<BackupHeader>> sets = backups.headers(path);
            if (sets.isEmpty()) {
                problem = "ملف النسخة غير موجود أو لا يستطيع خادم SQL Server الوصول إليه.";
            } else if (sets.get().size() != 1) {
                problem = "الملف يحتوي على أكثر من نسخة احتياطية.";
            } else {
                header = sets.get().get(0);
                if (!header.fullDatabase()) {
                    problem = "الملف ليس نسخة احتياطية كاملة لقاعدة البيانات.";
                } else if (!backup.databaseName().equalsIgnoreCase(header.databaseName())) {
                    problem = "الملف نسخة من قاعدة بيانات أخرى (" + header.databaseName() + ").";
                } else {
                    backups.verifyOnly(path, header.hasChecksums());
                }
            }
        } catch (DataAccessException e) {
            LOG.log(Level.WARNING, "Verification failed: " + backup.fileName(), e);
            problem = describe(e);
            refused = permissionDenied(e);
        } catch (IllegalArgumentException e) {
            problem = e.getMessage();
        }
        boolean valid = problem == null;
        if (refused) {
            // SQL Server would not let this login read the file: nothing is known about the file itself, so its
            // verification status stays as it was (never "failed" for a file that may be fine)
            LOG.warning("Backup " + backup.fileName() + " not verified: permission refused by SQL Server");
        } else {
            try {
                history.markVerification(backup.backupId(), valid ? Verification.VERIFIED : Verification.VERIFY_FAILED,
                        actor.name(), problem);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Could not save the verification of backup " + backup.backupId(), e);
            }
        }
        audit(actor, valid ? AuditLogDao.BACKUP_VERIFIED : AuditLogDao.BACKUP_VERIFY_FAILED, backup.backupId(),
                backup.fileName() + (valid ? ": سليمة" : ": " + problem));
        return new BackupVerificationResult(backup.backupId(), backup.fileName(), valid,
                header == null ? null : header.databaseName(), header == null ? null : header.finishedAt(),
                header == null ? backup.sizeBytes() : Long.valueOf(header.sizeBytes()),
                valid ? "النسخة سليمة ويستطيع خادم SQL Server قراءتها بالكامل." : problem);
    }

    // ======================= restore =======================

    @Override
    public RestoreResult restore(int backupId, String typedConfirmation) {
        security.requirePermission(Permission.BACKUP_RESTORE);
        Actor actor = actor();
        String db = config.databaseName();
        BackupPolicy.checkConfirmation(typedConfirmation, db);
        String directory = readyDirectory();   // where the safety backup goes
        BackupInfo backup = find(backupId);
        String unusable = BackupPolicy.unusableReason(backup);
        if (unusable != null) {
            throw new ValidationException("backup", unusable);
        }
        if (!db.equalsIgnoreCase(backup.databaseName())) {
            throw new ValidationException("backup", "هذه النسخة تخص قاعدة بيانات أخرى (" + backup.databaseName() + ").");
        }
        String incompatible = BackupPolicy.incompatibility(backup.schemaVersion(), config.requiredSchemaVersion());
        if (incompatible != null) {
            throw new ValidationException("backup", incompatible);
        }
        try (Held ignored = lock()) {
            return restoreLocked(backup, directory, actor);
        }
    }

    private RestoreResult restoreLocked(BackupInfo backup, String directory, Actor actor) {
        String db = config.databaseName();
        int id = backup.backupId();
        String requested = "طلب " + actor.name() + " استعادة قاعدة البيانات " + db + " من النسخة " + backup.fileName();
        String requestedAt = serverTimeText();
        audit(actor, AuditLogDao.RESTORE_REQUESTED, id, requested);
        LOG.info("Restore requested by " + actor.name() + ": " + db + " from " + backup.fileName());

        // 1. SQL Server must be able to read the backup completely
        BackupVerificationResult check = verifyRow(backup, actor);
        if (!check.valid()) {
            throw failed(actor, id, "لم تتم الاستعادة لأن التحقق من النسخة فشل: " + check.message());
        }
        // 2. the backup's files must be this database's files (restored over them, in place)
        String path = BackupPolicy.serverPath(backup.serverDirectory(), backup.fileName());
        List<DatabaseFile> moves;
        try {
            moves = plan(path);
        } catch (DataAccessException e) {
            LOG.log(Level.WARNING, "Restore planning failed", e);
            throw failed(actor, id, "لم تتم الاستعادة: " + describe(e));
        }
        if (moves == null) {
            throw failed(actor, id, "لم تتم الاستعادة: ملفات النسخة لا تطابق ملفات قاعدة البيانات الحالية.");
        }
        // 3. a verified safety backup first — without it nothing is restored
        BackupInfo safety;
        try {
            safety = create(Kind.PRE_RESTORE, directory, actor);
        } catch (BackupException e) {
            throw failed(actor, id, "تعذّر إنشاء النسخة الوقائية قبل الاستعادة، فلم تتم الاستعادة. " + e.getMessage());
        }
        BackupVerificationResult safetyCheck = verifyRow(safety, actor);
        if (!safetyCheck.valid()) {
            throw failed(actor, id, "فشل التحقق من النسخة الوقائية، فلم تتم الاستعادة. " + safetyCheck.message());
        }
        String safetyText = "نسخة وقائية قبل استعادة " + backup.fileName() + ": " + safety.fileName();
        String safetyAt = serverTimeText();
        audit(actor, AuditLogDao.PRE_RESTORE_BACKUP_CREATED, safety.backupId(), safetyText);
        // 4. what the restored (older) database must get back afterwards
        List<BackupInfo> known = history.findAll();
        String safetyPath = BackupPolicy.serverPath(safety.serverDirectory(), safety.fileName());

        // 5. replace the database
        LOG.info("Restoring " + db + " from " + backup.fileName() + " (safety backup " + safety.fileName() + ")");
        try {
            backups.restore(path, moves);
        } catch (DataAccessException e) {
            LOG.log(Level.WARNING, "Restore failed: " + backup.fileName(), e);
            String why = describe(e);
            if (state().online()) {
                throw failed(actor, id, "فشلت الاستعادة ولم تتغير قاعدة البيانات: " + why);
            }
            throw rollBack(safety, safetyPath, moves, known, actor, "فشلت الاستعادة: " + why);
        }
        // 6. the data under this session has been replaced: it must not be used any more
        endSession.run();

        // 7. health of the restored database
        List<String> checks = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> critical = validate(checks, warnings);
        if (!critical.isEmpty()) {
            LOG.warning("Restored database failed validation: " + critical);
            throw rollBack(safety, safetyPath, moves, known, actor,
                    "قاعدة البيانات المستعادة لم تجتز فحص السلامة (" + String.join("؛ ", critical) + ").");
        }
        // 8. bring the history and the audit of what just happened into the restored database
        carryOver(known, actor, List.of(
                new String[]{AuditLogDao.RESTORE_REQUESTED, requested + " (سُجّل قبل الاستعادة في " + requestedAt + ")"},
                new String[]{AuditLogDao.PRE_RESTORE_BACKUP_CREATED, safetyText + " (في " + safetyAt + ")"},
                new String[]{AuditLogDao.RESTORE_COMPLETED, "تمت استعادة قاعدة البيانات " + db + " من النسخة "
                        + backup.fileName() + "؛ النسخة الوقائية: " + safety.fileName() + "؛ انتهت جلسة "
                        + actor.name() + (warnings.isEmpty() ? "" : "؛ ملاحظات: " + String.join("، ", warnings))}),
                warnings);
        LOG.info("Restore of " + db + " from " + backup.fileName() + " completed");
        return new RestoreResult(backup.fileName(), safety.fileName(), config.requiredSchemaVersion(), checks, warnings);
    }

    /** Logical file → current physical file of this database; {@code null} if the backup's files do not match. */
    private List<DatabaseFile> plan(String path) {
        Map<String, String> current = new HashMap<>();
        for (DatabaseFile f : backups.currentFiles()) {
            current.put(f.logicalName().toLowerCase(Locale.ROOT), f.physicalName());
        }
        List<DatabaseFile> inBackup = backups.filesInBackup(path);
        if (current.isEmpty() || inBackup.size() != current.size()) {
            return null;
        }
        List<DatabaseFile> moves = new ArrayList<>();
        for (DatabaseFile f : inBackup) {
            String target = current.get(f.logicalName().toLowerCase(Locale.ROOT));
            if (target == null) {
                return null;
            }
            moves.add(new DatabaseFile(f.logicalName(), target));
        }
        return moves;
    }

    /**
     * The restore left the database unusable: put back the safety backup (the exact state before the restore).
     * Always ends with an exception that says what the database is now.
     */
    private BackupException rollBack(BackupInfo safety, String safetyPath, List<DatabaseFile> moves,
                                     List<BackupInfo> known, Actor actor, String why) {
        endSession.run();
        try {
            backups.restore(safetyPath, moves);
        } catch (DataAccessException e) {
            LOG.log(Level.SEVERE, "Rollback to the safety backup " + safety.fileName() + " failed", e);
            return new BackupException(why + " لم تعد قاعدة البيانات متاحة، وتعذّرت إعادتها تلقائيًا: استعد النسخة الوقائية "
                    + safety.fileName() + " يدويًا على خادم SQL Server (راجع \"إجراء الاسترداد\" في README).");
        }
        if (!state().online()) {
            LOG.severe("Database not online after the rollback to " + safety.fileName());
            return new BackupException(why + " قاعدة البيانات ليست متاحة بعد إعادة النسخة الوقائية "
                    + safety.fileName() + ". راجع \"إجراء الاسترداد\" في README.");
        }
        carryOver(known, actor, List.<String[]>of(new String[]{AuditLogDao.RESTORE_FAILED, why
                + " أُعيدت قاعدة البيانات تلقائيًا إلى حالتها قبل الاستعادة من النسخة الوقائية " + safety.fileName()}),
                new ArrayList<>());
        LOG.warning("Restore failed; database put back from " + safety.fileName());
        return new BackupException(why + " أُعيدت قاعدة البيانات تلقائيًا إلى حالتها قبل الاستعادة (النسخة الوقائية "
                + safety.fileName() + ").");
    }

    /**
     * Read-only checks of the restored database. Returns the critical problems (the program cannot work with
     * the database); passed checks and consistency findings are added to the lists. Nothing is repaired.
     */
    private List<String> validate(List<String> checks, List<String> warnings) {
        List<String> critical = new ArrayList<>();
        DatabaseState st = state();
        if (!st.online()) {
            critical.add("قاعدة البيانات ليست متاحة (" + st.state() + ")");
            return critical;
        }
        checks.add("قاعدة البيانات متاحة (ONLINE)");
        if (!"MULTI_USER".equals(st.userAccess())) {
            backups.ensureMultiUser();
            if (!"MULTI_USER".equals(state().userAccess())) {
                critical.add("قاعدة البيانات لم تعد إلى وضع تعدد المستخدمين");
            }
        }
        if (critical.isEmpty()) {
            checks.add("وضع تعدد المستخدمين (MULTI_USER)");
        }
        Health health;
        try {
            health = backups.health();
        } catch (DataAccessException e) {
            LOG.log(Level.WARNING, "Post-restore health check failed", e);
            critical.add("تعذّر الاتصال بقاعدة البيانات المستعادة");
            return critical;
        }
        checks.add("الاتصال بقاعدة البيانات يعمل");
        if (health.schemaVersion() == null) {
            critical.add("جدول إصدار المخطط Schema_Info غير موجود");
        } else if (SettingsServiceImpl.compareVersions(health.schemaVersion(), config.requiredSchemaVersion()) != 0) {
            critical.add("إصدار المخطط " + health.schemaVersion() + " لا يطابق " + config.requiredSchemaVersion());
        } else {
            checks.add("إصدار المخطط " + health.schemaVersion());
        }
        if (!health.missingTables().isEmpty()) {
            critical.add("جداول أساسية مفقودة: " + String.join(", ", health.missingTables()));
        } else {
            checks.add("الجداول الأساسية موجودة");
        }
        if (health.missingTables().isEmpty()) {
            if (health.activeAdmins() < 1) {
                critical.add("لا يوجد مدير نظام فعّال للدخول");
            } else {
                checks.add("يوجد مدير نظام فعّال");
            }
            Map<String, String> labels = Map.of("stock", "منتجات لا يطابق رصيدها حركات المخزون",
                    "negativeStock", "منتجات برصيد سالب", "customers", "عملاء لا يطابق رصيدهم دفتر الحسابات",
                    "suppliers", "موردون لا يطابق رصيدهم دفتر الحسابات");
            health.consistency().forEach((key, count) -> {
                if (count > 0) {
                    warnings.add(labels.getOrDefault(key, key) + ": " + count);
                }
            });
            if (warnings.isEmpty()) {
                checks.add("فحوص الاتساق الأساسية (المخزون وأرصدة العملاء والموردين) سليمة");
            }
        }
        return critical;
    }

    /** Writes the known history and the given audit entries into the (restored) database; failures only logged. */
    private void carryOver(List<BackupInfo> known, Actor actor, List<String[]> entries, List<String> warnings) {
        try {
            history.merge(known);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not bring the backup history into the restored database", e);
            warnings.add("تعذّر تحديث سجل النسخ الاحتياطية في قاعدة البيانات المستعادة");
        }
        for (String[] entry : entries) {
            audit(actor, entry[0], null, entry[1]);
        }
    }

    private BackupException failed(Actor actor, int backupId, String message) {
        audit(actor, AuditLogDao.RESTORE_FAILED, backupId, message);
        LOG.warning("Restore not done: " + message);
        return new BackupException(message);
    }

    // ======================= helpers =======================

    private DatabaseState state() {
        try {
            return backups.state();
        } catch (DataAccessException e) {
            LOG.log(Level.WARNING, "Database state unknown", e);
            return new DatabaseState("UNKNOWN", null);
        }
    }

    private String serverTimeText() {
        try {
            return backups.serverTime().format(WHEN);
        } catch (DataAccessException e) {
            return LocalDateTime.now().format(WHEN);
        }
    }

    private BackupInfo find(int backupId) {
        return history.findById(backupId)
                .orElseThrow(() -> new ValidationException("backup", "النسخة الاحتياطية غير موجودة في السجل."));
    }

    private Actor actor() {
        UserSession session = security.requireSession();
        return new Actor(session.getUser().getUserId(), session.getUser().getUsername());
    }

    /** One operation at a time, here and on every other PC; never waits. */
    private Held lock() {
        if (!running.tryLock()) {
            throw new BackupException(BUSY);
        }
        try {
            Optional<AutoCloseable> server = backups.tryLock();
            if (server.isEmpty()) {
                throw new BackupException(BUSY);
            }
            AutoCloseable serverLock = server.get();
            return () -> {
                try {
                    serverLock.close();
                } catch (Exception e) {
                    LOG.log(Level.FINE, "Backup lock release", e);
                } finally {
                    running.unlock();
                }
            };
        } catch (DataAccessException e) {
            running.unlock();
            LOG.log(Level.WARNING, "Backup lock failed", e);
            throw new BackupException(NO_SERVER, e);
        } catch (RuntimeException e) {
            running.unlock();
            throw e;
        }
    }

    private void audit(Actor actor, String action, Integer backupId, String description) {
        try {
            history.audit(actor.userId(), action, backupId, description);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not write audit entry " + action, e);
        }
    }

    /** The Arabic summary of a SQL Server failure (technical details only in the log). */
    static String describe(RuntimeException e) {
        Throwable t = e;
        while (t != null && !(t instanceof SQLException)) {
            t = t.getCause();
        }
        if (!(t instanceof SQLException sql)) {
            return "تعذّر تنفيذ العملية على خادم SQL Server.";
        }
        StringBuilder text = new StringBuilder();
        for (SQLException x = sql; x != null; x = x.getNextException()) {
            text.append(x.getMessage()).append(' ');
        }
        return BackupPolicy.describe(sql.getErrorCode(), sql.getSQLState(), text.toString());
    }

    /** The failure is SQL Server refusing a permission (the file or folder may well be fine). */
    static boolean permissionDenied(RuntimeException e) {
        Throwable t = e;
        while (t != null && !(t instanceof SQLException)) {
            t = t.getCause();
        }
        return t instanceof SQLException sql && BackupPolicy.permissionDenied(sql.getErrorCode(), sql.getMessage());
    }

    /** "12.4 MB". */
    static String size(long bytes) {
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
