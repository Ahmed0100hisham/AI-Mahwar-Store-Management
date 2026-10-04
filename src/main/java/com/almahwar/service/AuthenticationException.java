package com.almahwar.service;

/**
 * Login was refused. {@link #getMessage()} is Arabic and safe to show the user:
 * it never reveals whether the username exists.
 */
public class AuthenticationException extends Exception {

    public enum Reason {
        INVALID_INPUT,
        INVALID_CREDENTIALS,
        ACCOUNT_DISABLED,
        ACCOUNT_LOCKED,
        NO_PERMISSIONS
    }

    private final Reason reason;

    public AuthenticationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
