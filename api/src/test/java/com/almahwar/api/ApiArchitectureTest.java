package com.almahwar.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Layering rules, checked on the sources:
 * <ul>
 *   <li>controllers contain no SQL and touch neither JDBC nor repositories (controller → service → repository);</li>
 *   <li>every business route is versioned under {@code /api/v1};</li>
 *   <li>no JPA / Hibernate; no Flutter / Dart anywhere in the API project;</li>
 *   <li>SQL is built only in repositories.</li>
 * </ul>
 */
class ApiArchitectureTest {

    private static final Path MAIN = Path.of("src", "main", "java", "com", "almahwar", "api");
    private static final Pattern SQL = Pattern.compile("\\b(SELECT|INSERT\\s+INTO|UPDATE\\s+dbo|DELETE\\s+FROM|EXEC\\s|dbo\\.)",
            Pattern.CASE_INSENSITIVE);

    private static List<Path> sources() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void controllersHaveNoSqlAndNoDataAccess() throws IOException {
        List<Path> controllers = sources().stream().filter(p -> p.getFileName().toString().endsWith("Controller.java"))
                .toList();
        assertThat(controllers).hasSizeGreaterThanOrEqualTo(3);
        for (Path c : controllers) {
            String src = read(c);
            assertThat(SQL.matcher(src).find()).as(c + " contains SQL").isFalse();
            assertThat(src).as(c.toString()).doesNotContain("java.sql").doesNotContain("javax.sql")
                    .doesNotContain("JdbcClient").doesNotContain("JdbcTemplate").doesNotContain("DataSource")
                    .doesNotContain("Repository;").doesNotContain("Repository ");
        }
    }

    @Test
    void sqlOnlyInRepositories() throws IOException {
        for (Path p : sources()) {
            String name = p.getFileName().toString();
            if (name.endsWith("Repository.java")) {
                continue;
            }
            assertThat(read(p)).as(name).doesNotContain("JdbcClient").doesNotContain("JdbcTemplate")
                    .doesNotContain("PreparedStatement");
        }
    }

    @Test
    void routesAreVersioned() throws IOException {
        Pattern mapping = Pattern.compile("@RequestMapping\\(\"([^\"]+)\"\\)");
        for (Path p : sources()) {
            var m = mapping.matcher(read(p));
            while (m.find()) {
                assertThat(m.group(1)).as(p.toString()).startsWith("/api/v1/");
            }
        }
    }

    @Test
    void noJpaAndNoFlutter() throws IOException {
        String dependencies = String.join(" ", Files.readAllLines(Path.of("pom.xml")).stream()
                .filter(l -> l.contains("<artifactId>") || l.contains("<groupId>")).toList());
        assertThat(dependencies).doesNotContain("data-jpa").doesNotContain("hibernate").doesNotContainIgnoringCase("flutter");
        try (Stream<Path> all = Files.walk(Path.of("."))) {
            assertThat(all.filter(p -> !p.startsWith(Path.of(".", "target")))
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".dart") || n.equals("pubspec.yaml"))).isEmpty();
        }
    }
}
