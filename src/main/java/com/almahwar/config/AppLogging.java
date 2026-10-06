package com.almahwar.config;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Technical application log (not the business {@code Audit_Log}): rotating files, so a program started without a
 * console (double-click, installer) still keeps a record of startup, shutdown, database problems and unexpected
 * errors.
 * <ul>
 *   <li>Folder: {@code app.log.directory}, default {@code %LOCALAPPDATA%\AlMahwar\logs} on Windows
 *       ({@code ~/.almahwar/logs} elsewhere) — writable without administrator rights, also when the program is
 *       installed under Program Files.</li>
 *   <li>Files {@code almahwar-<generation>.<instance>.log} ({@code almahwar-0.0.log} is the current one), rotated at
 *       {@code app.log.max-file-mb} (default 5 MB), at most {@code app.log.files} files (default 5) — the log never
 *       grows without limit. A second program instance on the same PC writes its own file set
 *       ({@code almahwar-0.1.log}), never into the other's.</li>
 *   <li>Every line passes {@link #redact(String)}: anything that looks like {@code password=…} / {@code pwd=…} is
 *       masked, as a second line of defence (the program never logs secrets on purpose).</li>
 * </ul>
 * Logging problems never stop the program: it then logs to the console only.
 */
public final class AppLogging {

    private static final Pattern SECRET = Pattern.compile(
            "(?i)((?:password|passwd|pwd|secret|token)\\s*[=:]\\s*)([^;\\s,'\"]+)");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    private static volatile Path directory;

    private AppLogging() {
    }

    /** Installs the file log once (safe to call again). Returns the log folder, or {@code null} if unavailable. */
    public static synchronized Path init() {
        if (directory != null) {
            return directory;
        }
        AppConfig cfg = AppConfig.getInstance();
        Path dir = logDirectory(cfg.get("app.log.directory"), System.getenv("LOCALAPPDATA"),
                System.getProperty("user.home"));
        int maxMb = Math.max(1, Math.min(100, cfg.getInt("app.log.max-file-mb", 5)));
        int files = Math.max(1, Math.min(50, cfg.getInt("app.log.files", 5)));
        Logger root = Logger.getLogger("");
        for (Handler h : root.getHandlers()) {
            h.setFormatter(new LineFormatter());   // the console gets the same safe, redacted lines
        }
        try {
            Files.createDirectories(dir);
            FileHandler file = new FileHandler(dir.resolve("almahwar-%g.%u.log").toString(), maxMb * 1024 * 1024,
                    files, true);
            file.setEncoding("UTF-8");
            file.setFormatter(new LineFormatter());
            file.setLevel(Level.INFO);
            root.addHandler(file);
            directory = dir;
        } catch (IOException | RuntimeException e) {
            Logger.getLogger(AppLogging.class.getName()).log(Level.WARNING,
                    "File log unavailable (" + dir + "); logging to the console only", e);
            return null;
        }
        return directory;
    }

    /** The log folder in use, or {@code null}. */
    public static Path directory() {
        return directory;
    }

    /** Package-private for tests: the configured folder, else the per-user application data folder. */
    static Path logDirectory(String configured, String localAppData, String userHome) {
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim());
        }
        if (localAppData != null && !localAppData.isBlank()) {
            return Path.of(localAppData, "AlMahwar", "logs");
        }
        return Path.of(userHome == null ? "." : userHome, ".almahwar", "logs");
    }

    /** Masks values that look like secrets ({@code password=abc} → {@code password=*****}). */
    public static String redact(String text) {
        return text == null ? null : SECRET.matcher(text).replaceAll("$1*****");
    }

    /** One line per record: time, level, thread, logger, message; then the stack trace if any (all redacted). */
    static final class LineFormatter extends Formatter {
        @Override
        public String format(LogRecord r) {
            StringBuilder sb = new StringBuilder(160);
            sb.append(TIME.format(Instant.ofEpochMilli(r.getMillis()))).append(' ')
                    .append(r.getLevel().getName()).append(" [").append(Thread.currentThread().getName()).append("] ")
                    .append(r.getLoggerName()).append(": ").append(redact(formatMessage(r))).append(System.lineSeparator());
            if (r.getThrown() != null) {
                StringWriter sw = new StringWriter();
                r.getThrown().printStackTrace(new PrintWriter(sw));
                sb.append(redact(sw.toString()));
            }
            return sb.toString();
        }
    }
}
