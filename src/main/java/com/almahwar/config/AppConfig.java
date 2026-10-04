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
 * Loads application settings from {@code application.properties}.
 * <p>
 * Settings are read from the classpath first, then overridden by
 * {@code ./config/application.properties} if it exists, and finally by JVM
 * system properties ({@code -Dkey=value}), so database
 * credentials can be changed on a client machine without rebuilding.
 * Files are read as UTF-8 to support Arabic values.
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
        if (!Files.isRegularFile(EXTERNAL_FILE)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(EXTERNAL_FILE, StandardCharsets.UTF_8)) {
            properties.load(reader);
            LOG.info("Loaded external configuration: " + EXTERNAL_FILE.toAbsolutePath());
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed to read external configuration " + EXTERNAL_FILE.toAbsolutePath(), e);
        }
    }

    /** JVM system properties (e.g. {@code -Ddb.host=server}) take precedence over the files. */
    private String raw(String key) {
        String value = System.getProperty(key);
        return value != null ? value : properties.getProperty(key);
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

    public String appVersion() {
        return get("app.version", "1.0.0");
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
