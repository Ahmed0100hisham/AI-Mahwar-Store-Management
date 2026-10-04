package com.almahwar;

/**
 * Entry point for the runnable fat jar.
 * <p>
 * The JVM refuses to start a class that extends {@code Application} from the
 * classpath when JavaFX is not on the module path, so the jar's Main-Class
 * points here and delegates to {@link MainApp}.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        MainApp.main(args);
    }
}
