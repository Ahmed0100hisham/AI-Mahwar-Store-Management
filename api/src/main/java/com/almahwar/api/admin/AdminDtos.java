package com.almahwar.api.admin;

import com.almahwar.model.UserAccount;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.Set;

/** Explicit transport contracts; core entities and credential state are never serialized. */
public final class AdminDtos {
    private AdminDtos() { }
    public record User(int id, String username, String fullName, String phone, String email,
                       String roleCode, String roleName, boolean active, boolean mustChangePassword,
                       LocalDateTime createdAt, LocalDateTime lastLoginAt) {
        static User from(UserAccount u) {
            return new User(u.userId(),u.username(),u.fullName(),u.phone(),u.email(),u.roleCode(),u.roleName(),
                    u.active(),u.mustChangePassword(),u.createdAt(),u.lastLoginAt());
        }
    }
    public record Create(@NotBlank @Size(max=50) String username, @NotBlank @Size(max=100) String fullName,
                         @Size(max=100) String phone, @Size(max=100) String email,
                         @NotBlank @Size(max=30) String roleCode, @NotBlank @Size(max=128) String password,
                         @NotBlank @Size(max=128) String confirmPassword) {
        @Override public String toString() { return "Create[redacted]"; }
    }
    public record Edit(@NotBlank @Size(max=100) String fullName, @Size(max=100) String phone,
                       @Size(max=100) String email, @NotBlank @Size(max=30) String roleCode,
                       @NotNull Boolean active) { }
    public record Reset(@NotBlank @Size(max=128) String password,
                        @NotBlank @Size(max=128) String confirmPassword) {
        @Override public String toString() { return "Reset[redacted]"; }
    }
    public record Role(String code, String name) { }
    public record Permission(String code, String description, String group, Set<String> roles) { }
    public record Audit(long id, LocalDateTime timestamp, Integer userId, String username,
                        String fullName, String action, String category) { }
}
