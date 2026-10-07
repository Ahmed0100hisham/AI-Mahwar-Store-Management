package com.almahwar;

import com.almahwar.service.SecurityContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The boundary of the reusable business core (packages {@code model}, {@code util}, {@code dao}, {@code service},
 * published as the {@code core} classifier JAR). The desktop UI may depend on the core, never the reverse; the core
 * must stay free of JavaFX, Spring and API code, and of hidden process-wide state.
 */
class CoreBoundaryTest {

    private static final Path SRC = Path.of("src", "main", "java", "com", "almahwar");
    private static final List<String> CORE = List.of("model", "util", "dao", "service");

    /** Desktop-only classes kept out of the core JAR (see the core-jar execution in pom.xml). */
    static final Set<String> DESKTOP_ONLY = Set.of("service/SessionManager", "service/AuthServiceImpl",
            "service/BackupRestoreServiceImpl", "service/HealthCheckServiceImpl", "service/SystemStatusServiceImpl",
            "dao/DatabaseBackupDao", "dao/BackupHistoryDao", "dao/DatabaseHealthDao");

    private static List<Path> sources(String pkg) {
        try (Stream<Path> files = Files.walk(SRC.resolve(pkg))) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The source without comments: Javadoc may mention desktop classes, code must not. */
    private static String code(Path p) {
        return read(p).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    private static String key(Path p) {
        return SRC.relativize(p).toString().replace('\\', '/').replace(".java", "");
    }

    @Test
    void coreImportsNoUiFrameworkOrApiCode() {
        List<String> violations = new ArrayList<>();
        for (String pkg : CORE) {
            for (Path p : sources(pkg)) {
                String src = code(p);
                for (String forbidden : List.of("javafx.", "java.awt.", "javax.swing.", "com.almahwar.controller.",
                        "com.almahwar.MainApp", "com.almahwar.Launcher", "org.springframework.", "com.almahwar.api.",
                        "jakarta.", "com.almahwar.config.AppContext", "com.almahwar.config.AppLogging")) {
                    if (src.contains("import " + forbidden) || src.contains(" " + forbidden)) {
                        violations.add(key(p) + " -> " + forbidden);
                    }
                }
            }
        }
        assertEquals(List.of(), violations);
    }

    @Test
    void reusableCoreClassesDoNotReachDesktopOnlyClasses() {
        List<String> violations = new ArrayList<>();
        for (String pkg : CORE) {
            for (Path p : sources(pkg)) {
                if (DESKTOP_ONLY.contains(key(p))) {
                    continue;
                }
                String src = code(p);
                for (String desktopOnly : DESKTOP_ONLY) {
                    String simple = desktopOnly.substring(desktopOnly.indexOf('/') + 1);
                    if (src.matches("(?s).*\\b" + simple + "\\b.*")) {
                        violations.add(key(p) + " -> " + simple);
                    }
                }
            }
        }
        assertEquals(List.of(), violations);
    }

    @Test
    void connectionsComeOnlyFromTheConnectionSeam() {
        // the static desktop connection is reached only through ConnectionSource (and by desktop-only admin classes)
        List<String> direct = new ArrayList<>();
        for (String pkg : CORE) {
            for (Path p : sources(pkg)) {
                if (code(p).contains("DatabaseConnection.getConnection(") && !DESKTOP_ONLY.contains(key(p))
                        && !key(p).equals("dao/ConnectionSource")) {
                    direct.add(key(p));
                }
            }
        }
        assertEquals(List.of(), direct, "use ConnectionSource.open()");
        assertTrue(read(SRC.resolve("dao/ConnectionSource.java")).contains("DatabaseConnection::getConnection"));
    }

    @Test
    void businessServicesReceiveTheSecurityContext() throws Exception {
        for (String name : List.of("Sale", "Purchase", "Return", "Quotation", "Payment", "Expense", "Cashbox",
                "Inventory", "Product", "Catalog", "Customer", "Supplier", "User", "Dashboard", "Report", "Settings")) {
            Class<?> c = Class.forName("com.almahwar.service." + name + "ServiceImpl");
            boolean every = Arrays.stream(c.getConstructors()).allMatch(k -> Arrays.asList(k.getParameterTypes())
                    .contains(SecurityContext.class));
            assertTrue(c.getConstructors().length > 0 && every, name + "ServiceImpl must take a SecurityContext");
            for (Constructor<?> k : c.getConstructors()) {
                for (Class<?> t : k.getParameterTypes()) {
                    assertTrue(!t.getName().equals("com.almahwar.service.SessionManager"),
                            name + "ServiceImpl must not depend on the desktop session");
                }
            }
        }
    }

    @Test
    void noHiddenMutableGlobalState() throws Exception {
        // the only process-wide mutable state of the reusable core is the connection seam itself
        Set<String> allowed = Set.of("com.almahwar.dao.ConnectionSource.provider");
        List<String> found = new ArrayList<>();
        for (String pkg : CORE) {
            for (Path p : sources(pkg)) {
                if (DESKTOP_ONLY.contains(key(p))) {
                    continue;
                }
                Class<?> c = Class.forName("com.almahwar." + key(p).replace('/', '.'));
                for (Field f : c.getDeclaredFields()) {
                    int m = f.getModifiers();
                    if (Modifier.isStatic(m) && !Modifier.isFinal(m) && !f.isSynthetic()) {
                        found.add(c.getName() + "." + f.getName());
                    }
                }
            }
        }
        found.removeAll(allowed);
        assertEquals(List.of(), found);
    }
}
