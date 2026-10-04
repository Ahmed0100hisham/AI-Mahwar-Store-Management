package com.almahwar.service;

/** Thrown when the current user is not logged in or lacks a permission. Message is Arabic. */
public class AccessDeniedException extends RuntimeException {

    public AccessDeniedException(String message) {
        super(message);
    }
}
