package com.almahwar.controller.support;

import javafx.concurrent.Task;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Runs service calls off the JavaFX thread so the window never freezes while
 * SQL Server answers, then hands the result back on the JavaFX thread.
 */
public final class Async {

    private Async() {
    }

    public static <T> void run(Supplier<T> work, Consumer<T> onSuccess, Consumer<Throwable> onError) {
        Task<T> task = new Task<>() {
            @Override
            protected T call() {
                return work.get();
            }
        };
        task.setOnSucceeded(e -> onSuccess.accept(task.getValue()));
        task.setOnFailed(e -> onError.accept(task.getException()));
        Thread thread = new Thread(task, "ui-task");
        thread.setDaemon(true);
        thread.start();
    }

    /** For calls that return nothing. */
    public static void run(Runnable work, Runnable onSuccess, Consumer<Throwable> onError) {
        run(() -> {
            work.run();
            return null;
        }, ignored -> onSuccess.run(), onError);
    }
}
