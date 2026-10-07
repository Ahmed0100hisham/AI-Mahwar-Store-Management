package com.almahwar.api.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/v1/auth/login}. Limits follow the database ({@code Users.username NVARCHAR(50)}) and the desktop
 * password policy (at most 128 characters) — which also bounds the hashing work one request can cause.
 */
public record LoginRequest(
        @NotBlank(message = "أدخل اسم المستخدم.") @Size(max = 50, message = "اسم المستخدم طويل جدًا.") String username,
        @NotBlank(message = "أدخل كلمة المرور.") @Size(max = 128, message = "كلمة المرور طويلة جدًا.") String password,
        @Size(max = 100) String deviceLabel) {

    /** Never print the password (records print every component by default). */
    @Override
    public String toString() {
        return "LoginRequest[username=" + username + ", password=****]";
    }
}
