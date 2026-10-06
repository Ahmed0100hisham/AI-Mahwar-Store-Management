package com.almahwar.api.error;

import org.springframework.http.HttpStatus;

/**
 * Stable, machine-readable error codes of the API ({@link ApiError#code()}). Clients branch on the code, never on the
 * (Arabic, user-facing) message. Adding a code is fine; renaming one breaks clients.
 */
public enum ErrorCode {

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "البيانات المرسلة غير صحيحة."),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "تعذّر قراءة الطلب."),

    /** No / invalid / expired token. */
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "يجب تسجيل الدخول."),
    /** Wrong username or password: one message for both, so usernames cannot be discovered. */
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "اسم المستخدم أو كلمة المرور غير صحيحة."),
    /** The token was valid but the account changed since (disabled, password changed or reset, deleted). */
    SESSION_REVOKED(HttpStatus.UNAUTHORIZED, "انتهت صلاحية الجلسة. سجّل الدخول من جديد."),
    /** Too many wrong passwords: the account (or unknown username) is locked for a while — same rule as the desktop. */
    ACCOUNT_LOCKED(HttpStatus.TOO_MANY_REQUESTS, "تم إيقاف تسجيل الدخول مؤقتًا بسبب كثرة المحاولات الخاطئة."),
    /** Correct password, but the account is disabled (shown only after a correct password, as on the desktop). */
    ACCOUNT_DISABLED(HttpStatus.FORBIDDEN, "هذا الحساب موقوف. يرجى التواصل مع مدير النظام."),
    /** The user's role has no permissions (e.g. MANAGER): cannot log in, as on the desktop. */
    NO_PERMISSIONS(HttpStatus.FORBIDDEN, "لا توجد صلاحيات مرتبطة بدور هذا المستخدم. يرجى التواصل مع مدير النظام."),
    /** must_change_password: only the password change is allowed until it is done. */
    PASSWORD_CHANGE_REQUIRED(HttpStatus.FORBIDDEN, "يجب تغيير كلمة المرور قبل المتابعة."),
    /** Logged in, but without the permission this operation needs. */
    FORBIDDEN(HttpStatus.FORBIDDEN, "ليست لديك صلاحية لتنفيذ هذه العملية."),

    NOT_FOUND(HttpStatus.NOT_FOUND, "المورد المطلوب غير موجود."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "طريقة الطلب غير مدعومة."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "نوع المحتوى غير مدعوم."),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "طلبات كثيرة. حاول بعد قليل."),

    /** The database cannot be reached right now (no details are given to the client). */
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "الخدمة غير متاحة حاليًا. حاول لاحقًا."),
    /** Anything unexpected: details are only in the server log, under the request id. */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "حدث خطأ غير متوقع. حاول لاحقًا.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
