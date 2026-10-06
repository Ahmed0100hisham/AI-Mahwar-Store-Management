package com.almahwar.service;

import com.almahwar.model.BackupInfo;
import com.almahwar.model.BackupInfo.Kind;
import com.almahwar.model.BackupInfo.Status;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The rules of backup / restore that need no database (unit-tested): names, the server-side folder, file names,
 * the restore confirmation, status changes, schema compatibility and the Arabic wording of SQL Server errors.
 * <p>
 * Values reach SQL Server only as statement parameters; these checks are a second line of defence and keep
 * anything that is not a plain name or path out of the backup statements altogether.
 */
public final class BackupPolicy {

    /** A database name the program will back up / restore: letters, digits and underscores only. */
    private static final Pattern DATABASE_NAME = Pattern.compile("[A-Za-z0-9_]{1,100}");
    private static final Pattern PREFIX = Pattern.compile("[A-Za-z0-9_-]{1,50}");
    /** Every file name the program creates: PREFIX_yyyy-MM-dd_HHmmss[_n].bak, PRE_RESTORE_ for safety backups. */
    private static final Pattern FILE_NAME = Pattern.compile(
            "(PRE_RESTORE_)?[A-Za-z0-9_-]{1,50}_\\d{4}-\\d{2}-\\d{2}_\\d{6}(_\\d{1,3})?\\.bak");
    /** Folder characters: letters, digits, space and _ - . : \ / $ (Windows drive / UNC share, or a Linux path). */
    private static final Pattern DIRECTORY_CHARS = Pattern.compile("[A-Za-z0-9 _\\-.:\\\\/$]+");
    private static final Pattern WINDOWS_ABSOLUTE = Pattern.compile("[A-Za-z]:[\\\\/].*");
    private static final Pattern UNC = Pattern.compile("\\\\\\\\[^\\\\/]+[\\\\/][^\\\\/]+.*");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss", Locale.ROOT);

    public static final int MAX_DIRECTORY_LENGTH = 300;
    public static final String SAFETY_PREFIX = "PRE_RESTORE_";

    private BackupPolicy() {
    }

    // ---------- names ----------

    /** @throws IllegalArgumentException unless a plain database name */
    public static String databaseName(String name) {
        if (name == null || !DATABASE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("اسم قاعدة البيانات غير صالح للنسخ الاحتياطي (حروف إنجليزية وأرقام و _ فقط).");
        }
        return name;
    }

    /** The configured prefix, or the database name when none is set. */
    public static String prefix(String configured, String databaseName) {
        String p = configured == null || configured.isBlank() ? databaseName : configured.trim();
        if (!PREFIX.matcher(p).matches()) {
            throw new IllegalArgumentException("بادئة اسم ملف النسخة (backup.file-prefix) غير صالحة: حروف إنجليزية وأرقام و _ - فقط.");
        }
        return p;
    }

    /**
     * The file name of a backup taken at {@code serverTime} (SQL Server's clock, the same for every PC), e.g.
     * {@code AlMahwarDB_2026-10-06_173500.bak}; {@code attempt} &gt; 0 adds {@code _2}, {@code _3} … when that
     * name is already taken, so an existing file is never overwritten.
     */
    public static String fileName(Kind kind, String prefix, LocalDateTime serverTime, int attempt) {
        String base = (kind == Kind.PRE_RESTORE ? SAFETY_PREFIX : "") + prefix + "_" + serverTime.format(STAMP);
        return base + (attempt > 0 ? "_" + (attempt + 1) : "") + ".bak";
    }

    /** Only files named by the program can be verified or restored (never a free path typed by someone). */
    public static boolean isProgramFileName(String fileName) {
        return fileName != null && FILE_NAME.matcher(fileName).matches();
    }

    // ---------- the server-side folder ----------

    /**
     * Checks the backup folder as SQL Server sees it: an absolute Windows path ({@code D:\SQLBackups}), UNC share
     * ({@code \\server\share\backups}) or Linux path ({@code /var/opt/mssql/backup}) of plain characters, without
     * {@code ..}. Returns it without a trailing separator.
     *
     * @throws IllegalArgumentException with an Arabic reason
     */
    public static String directory(String directory) {
        if (directory == null || directory.isBlank()) {
            throw new IllegalArgumentException("لم يُحدَّد مجلد النسخ الاحتياطي على خادم SQL Server (backup.server-directory).");
        }
        String d = directory.trim();
        if (d.length() > MAX_DIRECTORY_LENGTH || !DIRECTORY_CHARS.matcher(d).matches()) {
            throw new IllegalArgumentException("مسار مجلد النسخ الاحتياطي يحتوي على أحرف غير مسموح بها.");
        }
        if (!(d.startsWith("/") || WINDOWS_ABSOLUTE.matcher(d).matches() || UNC.matcher(d).matches())) {
            throw new IllegalArgumentException("مسار مجلد النسخ الاحتياطي يجب أن يكون مسارًا كاملًا على جهاز خادم SQL Server.");
        }
        for (String part : d.split("[\\\\/]")) {
            if (part.equals("..") || part.equals(".")) {
                throw new IllegalArgumentException("مسار مجلد النسخ الاحتياطي لا يجوز أن يحتوي على . أو ..");
            }
        }
        while (d.length() > 1 && (d.endsWith("/") || d.endsWith("\\")) && !d.matches("[A-Za-z]:[\\\\/]")) {
            d = d.substring(0, d.length() - 1);
        }
        return d;
    }

    /** The full path of a backup file on the SQL Server machine (that machine's separator). */
    public static String serverPath(String directory, String fileName) {
        if (!isProgramFileName(fileName)) {
            throw new IllegalArgumentException("اسم ملف النسخة الاحتياطية غير صالح.");
        }
        String d = directory(directory);
        String separator = d.startsWith("/") ? "/" : "\\";
        return d.endsWith(separator) ? d + fileName : d + separator + fileName;
    }

    // ---------- restore ----------

    /**
     * Restore needs the database name typed exactly (case included) — one click is never enough.
     *
     * @throws ValidationException field {@code confirmation}
     */
    public static void checkConfirmation(String typed, String databaseName) {
        if (typed == null || !typed.strip().equals(databaseName)) {
            throw new ValidationException(BackupRestoreService.CONFIRMATION,
                    "للتأكيد اكتب اسم قاعدة البيانات كما هو تمامًا: " + databaseName);
        }
    }

    /**
     * A backup can replace the database only if it was made with the schema this program uses: an older schema
     * would leave the program on tables it does not expect, a newer one was made by a newer program.
     *
     * @return {@code null} when compatible, otherwise the Arabic reason
     */
    public static String incompatibility(String backupSchema, String programSchema) {
        if (backupSchema == null || backupSchema.isBlank()) {
            return "إصدار مخطط قاعدة البيانات في هذه النسخة غير معروف، فلا يمكن استعادتها بأمان من البرنامج.";
        }
        int c = SettingsServiceImpl.compareVersions(backupSchema, programSchema);
        if (c > 0) {
            return "النسخة أُنشئت بمخطط أحدث (" + backupSchema + ") من الذي يستخدمه هذا البرنامج (" + programSchema
                    + "). حدّث البرنامج أولًا.";
        }
        if (c < 0) {
            return "النسخة أُنشئت بمخطط أقدم (" + backupSchema + ") من الذي يستخدمه هذا البرنامج (" + programSchema
                    + "). تُستعاد يدويًا على خادم SQL Server ثم يُشغَّل سكربت الترقية (راجع README).";
        }
        return null;
    }

    /** The only status changes of a backup row: CREATING → COMPLETED or FAILED. */
    public static boolean canMove(Status from, Status to) {
        return from == Status.CREATING && (to == Status.COMPLETED || to == Status.FAILED);
    }

    /** Verify / restore only what SQL Server finished writing, and only program-named files. */
    public static String unusableReason(BackupInfo backup) {
        if (backup.status() != Status.COMPLETED) {
            return "لا يمكن استخدام نسخة حالتها \"" + backup.status().getLabelAr() + "\".";
        }
        if (!isProgramFileName(backup.fileName())) {
            return "اسم ملف النسخة غير صالح.";
        }
        return null;
    }

    // ---------- SQL Server errors in Arabic ----------

    /** SQL Server refused the operation for lack of permission (not a problem of the file or the folder). */
    public static boolean permissionDenied(int errorCode, String text) {
        return errorCode == 262 || errorCode == 3110 || errorCode == 15247
                || (text != null && text.contains("permission denied"));
    }

    /**
     * A short Arabic explanation of a SQL Server backup / restore error, without technical details or secrets
     * (the technical text goes to the application log only).
     *
     * @param errorCode SQL Server error number of the first error
     * @param sqlState  JDBC SQL state ({@code 08…} = connection problem)
     * @param text      all error messages of the failure joined
     */
    public static String describe(int errorCode, String sqlState, String text) {
        String t = text == null ? "" : text;
        if (t.contains("CREATE DATABASE permission denied")) {
            // SQL Server needs it to read any backup file (RESTORE HEADERONLY / VERIFYONLY), even without restoring
            return "حساب قاعدة البيانات الذي يستخدمه البرنامج لا يملك صلاحية قراءة ملفات النسخ الاحتياطي على SQL Server "
                    + "(يلزم إذن CREATE DATABASE في قاعدة master — راجع README، قسم صلاحيات حساب SQL).";
        }
        if (t.contains("Operating system error 5") || t.contains("Access is denied")) {
            // SQL Server on Linux also reports a missing folder this way
            return "مجلد النسخ الاحتياطي غير موجود على جهاز خادم SQL Server، أو لا يملك SQL Server صلاحية الوصول إليه.";
        }
        if (t.contains("Operating system error 3")) {
            return "مجلد النسخ الاحتياطي غير موجود على جهاز خادم SQL Server.";
        }
        if (t.contains("Operating system error 2")) {
            return "ملف النسخة الاحتياطية غير موجود على جهاز خادم SQL Server.";
        }
        if (errorCode == 3101 || t.contains("Exclusive access could not be obtained")) {
            return "قاعدة البيانات مستخدمة ولم يمكن الحصول على وصول حصري إليها. أغلق البرنامج على الأجهزة الأخرى ثم حاول مرة أخرى.";
        }
        if (errorCode == 262 || errorCode == 3110 || errorCode == 15247 || t.contains("permission")) {
            return "حساب قاعدة البيانات الذي يستخدمه البرنامج لا يملك صلاحية النسخ الاحتياطي أو الاستعادة على SQL Server.";
        }
        if (errorCode == 3241 || errorCode == 3242 || errorCode == 3183 || errorCode == 3189 || errorCode == 3203
                || errorCode == 3266 || t.contains("checksum") || t.contains("not formatted correctly")
                || t.contains("damaged") || t.contains("corrupt")) {
            return "ملف النسخة الاحتياطية تالف أو ليس نسخة احتياطية صالحة.";
        }
        if (sqlState != null && sqlState.startsWith("08")) {
            return "انقطع الاتصال بخادم SQL Server أثناء العملية.";
        }
        return "تعذّر تنفيذ العملية على خادم SQL Server" + (errorCode > 0 ? " (رمز الخطأ " + errorCode + ")." : ".");
    }
}
