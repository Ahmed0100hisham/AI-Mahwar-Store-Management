package com.almahwar;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards the layering described in ARCHITECTURE.md by scanning the sources'
 * import statements, so a violation fails the build instead of slipping in.
 * <p>
 * Model, DAO, Service, Config and Util must stay free of JavaFX so they can be
 * reused unchanged by the future REST API; controllers must not touch SQL.
 */
class ArchitectureTest {

    private static final Path SRC = Path.of("src", "main", "java", "com", "almahwar");

    private static List<String> violations(String layer, String... forbiddenPrefixes) {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SRC.resolve(layer))) {
            files.filter(f -> f.toString().endsWith(".java")).forEach(file -> {
                try {
                    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                        String l = line.trim();
                        if (!l.startsWith("import ")) {
                            continue;
                        }
                        String imported = l.substring(7).replace("static ", "");
                        for (String forbidden : forbiddenPrefixes) {
                            if (imported.startsWith(forbidden)) {
                                found.add(SRC.relativize(file) + " -> " + imported);
                            }
                        }
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return found;
    }

    @Test
    void coreLayersDoNotDependOnJavaFx() {
        for (String layer : new String[]{"model", "dao", "service", "config", "util"}) {
            assertEquals(List.of(), violations(layer, "javafx."), layer + " must not use JavaFX");
        }
    }

    @Test
    void controllersDoNotTouchTheDatabase() {
        assertEquals(List.of(), violations("controller",
                "java.sql.", "javax.sql.", "com.almahwar.dao.", "com.almahwar.config.DatabaseConnection"));
    }

    @Test
    void dependenciesPointInwards() {
        assertEquals(List.of(), violations("model",
                "com.almahwar.dao.", "com.almahwar.service.", "com.almahwar.controller.", "com.almahwar.config."));
        assertEquals(List.of(), violations("dao", "com.almahwar.service.", "com.almahwar.controller."));
        assertEquals(List.of(), violations("service", "com.almahwar.controller."));
        assertEquals(List.of(), violations("util", "com.almahwar.dao.", "com.almahwar.service.",
                "com.almahwar.controller.", "com.almahwar.config.AppContext"));
    }
}
