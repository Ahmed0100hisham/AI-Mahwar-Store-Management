package com.almahwar.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;

/** Test access to the package-private rules of {@link AppConfig} and {@link AppLogging}. */
public final class ConfigProbe {

    private ConfigProbe() {
    }

    public static String resolve(String key, Function<String, String> system, Function<String, String> env,
                                 Properties files) {
        return AppConfig.resolve(key, system, env, files);
    }

    public static List<String> databaseProblems(Function<String, String> get) {
        return AppConfig.databaseProblems(get);
    }

    public static java.util.Optional<Path> externalFile(String property, String env, List<Path> programFolders) {
        return AppConfig.externalFile(property, env, programFolders);
    }

    public static Path logDirectory(String configured, String localAppData, String userHome) {
        return AppLogging.logDirectory(configured, localAppData, userHome);
    }
}
