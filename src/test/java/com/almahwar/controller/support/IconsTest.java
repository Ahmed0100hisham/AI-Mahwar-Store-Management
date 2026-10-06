package com.almahwar.controller.support;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The icon system: every icon used by a view exists (a typo would otherwise fail only when the page opens), and no
 * button still uses a text symbol as its icon.
 */
class IconsTest {

    private static final Path FXML = Path.of("src", "main", "resources", "fxml");

    @Test
    void everyActionIconHasAValidPath() {
        for (String name : Icons.names()) {
            String path = Icons.path(name);
            assertTrue(path.startsWith("M") && path.length() > 10, name);
        }
        assertThrows(IllegalArgumentException.class, () -> Icons.path("no-such-icon"));
        assertThrows(IllegalArgumentException.class, () -> Icons.path(null));
    }

    @Test
    void everyIconUsedInTheViewsExists() throws IOException {
        Pattern use = Pattern.compile("<Icon name=\"([^\"]+)\"");
        List<String> used = new ArrayList<>();
        try (Stream<Path> files = Files.list(FXML)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".fxml")).toList()) {
                Matcher m = use.matcher(Files.readString(f, StandardCharsets.UTF_8));
                while (m.find()) {
                    used.add(m.group(1));
                    assertTrue(Icons.names().contains(m.group(1)), f.getFileName() + ": unknown icon " + m.group(1));
                }
            }
        }
        assertTrue(used.size() > 50, "icons are used across the views: " + used.size());
    }

    @Test
    void noButtonUsesATextSymbolAsIcon() throws IOException {
        Pattern symbolButton = Pattern.compile("<Button[^>]*text=\"(→|←|\\+ |− |✕|×|✔)");
        try (Stream<Path> files = Files.list(FXML)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".fxml")).toList()) {
                assertFalse(symbolButton.matcher(Files.readString(f, StandardCharsets.UTF_8)).find(),
                        f.getFileName() + " still has a text-symbol button");
            }
        }
    }

    @Test
    void sizesFollowTheIconScale() {
        assertEquals(14, Icon.SMALL);
        assertEquals(16, Icon.NORMAL);
        assertEquals(18, Icon.NAVIGATION);
    }
}
