package com.almahwar.service;

import com.almahwar.model.UserAccount;
import com.almahwar.model.UserAccount.Changes;
import com.almahwar.model.UserAccount.NewUser;
import com.almahwar.model.UserAccount.PermissionRow;

import java.util.List;
import java.util.Map;

/**
 * User administration (admin only): {@code USERS_VIEW}, {@code USERS_CREATE}, {@code USERS_EDIT},
 * {@code USERS_RESET_PASSWORD}. Users are never deleted (their documents and audit history stay attributable):
 * they are deactivated.
 * <p>
 * Safety rules, enforced here inside a transaction that locks the active administrators (so concurrent changes
 * wait for each other): there is always at least one active ADMIN; an admin cannot deactivate themself or change
 * their own role; the own password is changed through {@link AuthService#changePassword}, not reset.
 * Passwords are only ever stored as hashes; no password or hash is returned, logged or audited.
 */
public interface UserService {

    // Field names used in ValidationException.getErrors()
    String USERNAME = "username";
    String FULL_NAME = "fullName";
    String PHONE = "phone";
    String EMAIL = "email";
    String ROLE = "role";
    String PASSWORD = "password";
    String ACTIVE = "active";

    /** The roles a user can have (role code → Arabic name), in display order. */
    Map<String, String> roles();

    /** @param roleCode {@code null} = all; @param active {@code null} = all */
    List<UserAccount> search(String text, String roleCode, Boolean active);

    UserAccount findById(int userId);

    /** Creates a user; the password follows {@link CredentialPolicy} and is typed twice. Arrays are wiped. */
    UserAccount create(NewUser user, char[] password, char[] confirm);

    /** Saves name, phone, email, role and status (each change audited separately). */
    UserAccount update(Changes changes);

    UserAccount setActive(int userId, boolean active);

    /**
     * Sets a new (temporary) password chosen by the admin for another user; by default the user must change it at
     * the next login. Also lifts a temporary lock. Arrays are wiped.
     */
    UserAccount resetPassword(int userId, char[] password, char[] confirm, boolean mustChange);

    /** Lifts a temporary lock (too many wrong passwords). */
    UserAccount unlock(int userId);

    /** Every permission and the roles that hold it ({@code USERS_VIEW}). */
    List<PermissionRow> permissionMatrix();
}
