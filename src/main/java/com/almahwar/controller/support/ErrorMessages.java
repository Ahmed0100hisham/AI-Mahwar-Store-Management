package com.almahwar.controller.support;

import com.almahwar.service.AccessDeniedException;
import com.almahwar.service.BackupException;
import com.almahwar.service.ValidationException;

import java.util.logging.Level;
import java.util.logging.Logger;

/** Turns an exception from a service into an Arabic message for the user. */
public final class ErrorMessages {

    private static final Logger LOG = Logger.getLogger(ErrorMessages.class.getName());

    private ErrorMessages() {
    }

    public static String of(Throwable error) {
        if (error instanceof ValidationException || error instanceof AccessDeniedException
                || error instanceof BackupException) {
            return error.getMessage();
        }
        LOG.log(Level.WARNING, "Operation failed", error);
        if (error instanceof java.io.UncheckedIOException || error instanceof java.io.IOException) {
            // saving a file on this PC (e.g. an Excel export), not the database
            return "تعذّر حفظ الملف. تأكد أن الملف غير مفتوح في برنامج آخر (مثل Excel) وأن لديك صلاحية الكتابة في المجلد.";
        }
        return "تعذّر تنفيذ العملية. تحقق من الاتصال بقاعدة البيانات ثم حاول مرة أخرى.";
    }

    /** Shows the message in an error dialog. */
    public static void show(String title, Throwable error) {
        AlertUtil.error(title, of(error));
    }
}
