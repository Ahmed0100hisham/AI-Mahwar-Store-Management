package com.almahwar.service;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Golden behaviour test: runs {@link GoldenScenarios} on a fresh temporary database and compares the complete
 * normalised result with the record captured on Desktop <b>v1.0.0</b> ({@code c27b2e3}, schema 1.10.0).
 * <p>
 * Run (temporary database only — the test refuses anything else):
 * <pre>mvn test -Ddb.it=true -Dgolden=true -Ddb.name=AlMahwarGoldenIT -Dtest=GoldenBehaviorTest</pre>
 * {@code -Dgolden.record=true} (re)writes the record — only ever on the v1.0.0 baseline. The actual result of every
 * run is also written to {@code target/golden/actual.txt}.
 */
@EnabledIfSystemProperty(named = "golden", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GoldenBehaviorTest {

    static final Path RECORD = Path.of("src", "test", "resources", "golden", "desktop-1.0.0-golden.txt");

    @BeforeAll
    void createDatabase() throws Exception {
        GoldenDatabase.create();
    }

    @AfterAll
    void dropDatabase() throws Exception {
        GoldenDatabase.drop();
    }

    @Test
    void businessBehaviourIsIdenticalToTheV100Record() throws Exception {
        String actual = new GoldenScenarios().runAll();
        Path out = Path.of("target", "golden", "actual.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, actual, StandardCharsets.UTF_8);

        assertTrue(!actual.contains("outcome: UNEXPECTED"), "a scenario failed unexpectedly — see " + out);
        if (Boolean.getBoolean("golden.record")) {
            Files.createDirectories(RECORD.getParent());
            Files.writeString(RECORD, actual, StandardCharsets.UTF_8);
            return;
        }
        compare(Files.readString(RECORD, StandardCharsets.UTF_8), actual);
    }

    static void compare(String expected, String actual) {
        List<String> e = expected.lines().toList();
        List<String> a = actual.lines().toList();
        for (int i = 0; i < Math.min(e.size(), a.size()); i++) {
            if (!e.get(i).equals(a.get(i))) {
                fail("golden difference at line " + (i + 1) + "\n  expected: " + e.get(i) + "\n  actual:   " + a.get(i)
                        + "\n  (section: " + section(e, i) + ")");
            }
        }
        assertEquals(e.size(), a.size(), "golden line count");
    }

    private static String section(List<String> lines, int index) {
        for (int i = index; i >= 0; i--) {
            if (lines.get(i).startsWith("=== ")) {
                return lines.get(i);
            }
        }
        return "?";
    }
}
