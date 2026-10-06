package com.almahwar.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loads application settings. Precedence, highest first:
 * <ol>
 *   <li>JVM system properties ({@code -Ddb.password=...})</li>
 *   <li>environment variables: {@code ALMAHWAR_} + the key in upper case with dots and dashes as underscores
 *       ({@code db.password} → {@code ALMAHWAR_DB_PASSWORD})</li>
 *   <li>{@code ./config/application.properties} next to where the program starts (git-ignored)</li>
 *   <li>the bundled {@code application.properties}: safe, non-secret defaults only — it holds no credentials</li>
 * </ol>
 * Secrets (the database user and password) therefore never live in the source code. Files are read as UTF-8.
 */
public final class AppConfig {

    private static final Logger LOG = Logger.getLogger(AppConfig.class.getName());

    private static final String CLASSPATH_FILE = "/application.properties";
    private static final Path EXTERNAL_FILE = Path.of("config", "application.properties");

    private static final AppConfig INSTANCE = new AppConfig();

    private final Properties properties = new Properties();

    private AppConfig() {
        loadClasspath();
        loadExternal();
    }

    public static AppConfig getInstance() {
        return INSTANCE;
    }

    private void loadClasspath() {
        try (InputStream in = AppConfig.class.getResourceAsStream(CLASSPATH_FILE)) {
            if (in == null) {
                LOG.warning("application.properties not found on classpath; using defaults");
                return;
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed to read classpath application.properties", e);
        }
    }

    private void loadExternal() {
        java.util.Optional<Path> found = externalFile(System.getProperty("almahwar.config"), System.getenv("ALMAHWAR_CONFIG"),
                programFolders());
        if (found.isEmpty()) {
            LOG.info("No external configuration file (config/application.properties)");
            return;
        }
        Path file = found.get();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
            LOG.info("Loaded external configuration: " + file.toAbsolutePath());   // the path only, never the values
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed to read external configuration " + file.toAbsolutePath(), e);
        }
    }

    /**
     * The external file in use (package-private for unit tests), the first that exists of: an explicit path
     * ({@code -Dalmahwar.config=...} or {@code ALMAHWAR_CONFIG}), {@code config/application.properties} in the folder
     * the program starts from, then in the program's own folders (next to the JAR, or the installed application).
     */
    static java.util.Optional<Path> externalFile(String explicitProperty, String explicitEnv, java.util.List<Path> programFolders) {
        for (String explicit : new String[]{explicitProperty, explicitEnv}) {
            if (explicit != null && !explicit.isBlank()) {
                Path p = Path.of(explicit.trim());
                if (Files.isRegularFile(p)) {
                    return java.util.Optional.of(p);
                }
                LOG.warning("Configuration file not found: " + p.toAbsolutePath());
            }
        }
        java.util.List<Path> candidates = new java.util.ArrayList<>();
        candidates.add(EXTERNAL_FILE);
        for (Path folder : programFolders) {
            candidates.add(folder.resolve(EXTERNAL_FILE));
        }
        return candidates.stream().filter(Files::isRegularFile).findFirst();
    }

    /** The installed application's folder (jpackage) and the folder of the JAR, when known. */
    private static java.util.List<Path> programFolders() {
        java.util.List<Path> folders = new java.util.ArrayList<>();
        String appPath = System.getProperty("jpackage.app-path");   // set by the jpackage launcher: ...\AlMahwar.exe
        if (appPath != null && Path.of(appPath).getParent() != null) {
            folders.add(Path.of(appPath).getParent());
        }
        try {
            Path code = Path.of(AppConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(code) && code.getParent() != null) {   // running from a JAR
                folders.add(code.getParent());
                if (code.getParent().getParent() != null) {
                    folders.add(code.getParent().getParent());   // jpackage: <app>\app\the.jar
                }
            }
        } catch (Exception e) {
            // unknown location (e.g. a custom class loader): the working folder is still searched
        }
        return folders;
    }

    private String raw(String key) {
        return resolve(key, System::getProperty, System::getenv, properties);
    }

    /** Environment variable name of a key: {@code db.password} → {@code ALMAHWAR_DB_PASSWORD}. */
    public static String environmentName(String key) {
        return "ALMAHWAR_" + key.toUpperCase(java.util.Locale.ROOT).replace('.', '_').replace('-', '_');
    }

    /** The precedence rule (package-private for unit tests): system property, environment, files. */
    static String resolve(String key, java.util.function.Function<String, String> systemProperties,
                          java.util.function.Function<String, String> environment, Properties files) {
        String value = systemProperties.apply(key);
        if (value != null) {
            return value;
        }
        value = environment.apply(environmentName(key));
        if (value != null) {
            return value;
        }
        return files.getProperty(key);
    }

    public String get(String key, String defaultValue) {
        String value = raw(key);
        return value == null ? defaultValue : value.trim();
    }

    public String get(String key) {
        return get(key, "");
    }

    public int getInt(String key, int defaultValue) {
        String value = raw(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            LOG.warning("Invalid integer for '" + key + "': " + value);
            return defaultValue;
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String value = raw(key);
        return value == null ? defaultValue : Boolean.parseBoolean(value.trim());
    }

    // ---------- Convenience accessors ----------

    public String appName() {
        return get("app.name", "نظام إدارة شركة المحور");
    }

    public String appNameEn() {
        return get("app.name.en", "Al Mahwar Store Management System");
    }

    /**
     * The program version, from {@code version.properties} (filled by Maven with the project version at build
     * time) — the single version source.
     */
    public String appVersion() {
        return BuildInfo.VERSION;
    }

    /** Build information read once from {@code /version.properties}. */
    private static final class BuildInfo {
        static final String VERSION = load();

        private static String load() {
            Properties p = new Properties();
            try (java.io.InputStream in = AppConfig.class.getResourceAsStream("/version.properties")) {
                if (in != null) {
                    p.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
                }
            } catch (java.io.IOException ignored) {
                // falls back below
            }
            String v = p.getProperty("app.version", "");
            return v.isBlank() || v.startsWith("${") ? "dev" : v;
        }
    }

    // ---------- Validation ----------

    /**
     * What is missing or invalid in the database settings (Arabic, naming the setting — never its value), so the
     * program can say so instead of failing with an obscure SQL error. Empty when complete.
     */
    public java.util.List<String> databaseProblems() {
        return databaseProblems(this::raw);
    }

    /** The rule (package-private for unit tests): {@code get} returns the raw value of a key, or {@code null}. */
    static java.util.List<String> databaseProblems(java.util.function.Function<String, String> get) {
        java.util.List<String> problems = new java.util.ArrayList<>();
        String host = get.apply("db.host");
        if (blank(host)) {
            problems.add("عنوان خادم SQL Server غير محدد (db.host)");
        } else if (!host.trim().matches("[A-Za-z0-9._\\-\\\\]{1,253}")) {
            // a name, an IP address or SERVER\INSTANCE; ';' or '=' would change the connection settings
            problems.add("عنوان خادم SQL Server غير صالح (db.host)");
        }
        String port = get.apply("db.port");
        if (!blank(port)) {
            try {
                int p = Integer.parseInt(port.trim());
                if (p < 1 || p > 65535) {
                    problems.add("رقم المنفذ غير صالح (db.port)");
                }
            } catch (NumberFormatException e) {
                problems.add("رقم المنفذ غير صالح (db.port)");
            }
        }
        String name = get.apply("db.name");
        if (blank(name)) {
            problems.add("اسم قاعدة البيانات غير محدد (db.name)");
        } else if (!name.trim().matches("[A-Za-z0-9_]{1,100}")) {
            problems.add("اسم قاعدة البيانات غير صالح (db.name)");
        }
        boolean integrated = Boolean.parseBoolean(String.valueOf(get.apply("db.integrated-security")).trim());
        if (!integrated) {
            if (blank(get.apply("db.user"))) {
                problems.add("اسم مستخدم قاعدة البيانات غير محدد (db.user)");
            }
            if (blank(get.apply("db.password"))) {
                problems.add("كلمة مرور قاعدة البيانات غير محددة (db.password)");
            }
        }
        return problems;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    public String currencyCode() {
        return get("currency.code", "KWD");
    }

    public String currencySymbol() {
        return get("currency.symbol", "د.ك");
    }

    public int currencyScale() {
        return getInt("currency.scale", 3);
    }
}
