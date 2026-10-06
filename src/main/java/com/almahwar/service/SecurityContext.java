package com.almahwar.service;

import com.almahwar.model.Permission;
import com.almahwar.model.User;
import com.almahwar.model.UserSession;

import java.util.Optional;

/**
 * "Who is the current user, and what may they do?" — as seen by the services.
 * <p>
 * Services depend on this interface, never on a particular session store, so
 * the same business logic can run in:
 * <ul>
 *   <li>the desktop app: one user per process ({@link SessionManager});</li>
 *   <li>a future REST API: one user per HTTP request, resolved from the
 *       request's access token.</li>
 * </ul>
 */
public interface SecurityContext {

    /** The authenticated session, if any. */
    Optional<UserSession> getSession();

    default boolean isLoggedIn() {
        return getSession().isPresent();
    }

    /** @throws AccessDeniedException if nobody is logged in */
    default UserSession requireSession() {
        return getSession().orElseThrow(() -> new AccessDeniedException("يجب تسجيل الدخول أولًا."));
    }

    default User currentUser() {
        return requireSession().getUser();
    }

    default boolean hasPermission(Permission permission) {
        return getSession().map(s -> s.hasPermission(permission)).orElse(false);
    }

    default boolean hasAnyPermission(Permission... permissions) {
        for (Permission p : permissions) {
            if (hasPermission(p)) {
                return true;
            }
        }
        return false;
    }

    /** @throws AccessDeniedException unless the current user holds at least one of the permissions */
    default void requireAnyPermission(Permission... permissions) {
        UserSession session = requireSession();
        for (Permission p : permissions) {
            if (session.hasPermission(p)) {
                return;
            }
        }
        throw new AccessDeniedException("ليس لديك صلاحية الوصول إلى: " + permissions[0].getLabelAr());
    }

    /** @throws AccessDeniedException if the current user lacks the permission */
    default void requirePermission(Permission permission) {
        if (!requireSession().hasPermission(permission)) {
            throw new AccessDeniedException("ليس لديك صلاحية الوصول إلى: " + permission.getLabelAr());
        }
    }
}
