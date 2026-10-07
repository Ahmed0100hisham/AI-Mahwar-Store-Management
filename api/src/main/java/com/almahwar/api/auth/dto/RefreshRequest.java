package com.almahwar.api.auth.dto;

/** Format errors deliberately receive the same 401 as every invalid refresh credential. */
public record RefreshRequest(String refreshToken) {
    @Override public String toString() { return "RefreshRequest[****]"; }
}
