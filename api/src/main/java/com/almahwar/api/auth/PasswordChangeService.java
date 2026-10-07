package com.almahwar.api.auth;

import com.almahwar.api.audit.AuditLogRepository;
import com.almahwar.api.error.ApiException;
import com.almahwar.api.error.ErrorCode;
import com.almahwar.api.error.FieldValidationException;
import com.almahwar.api.security.ApiUser;
import com.almahwar.api.session.ApiSessionRepository;
import com.almahwar.service.CredentialPolicy;
import com.almahwar.util.PasswordHasher;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Business credential commits first; API revocation is a separate transaction. No distributed transaction. */
@Service
public class PasswordChangeService {
    private final AuthUserRepository users;
    private final ApiSessionRepository sessions;
    private final AuditLogRepository audit;
    private final Semaphore hashing = new Semaphore(AuthService.MAX_CONCURRENT_HASHES,true);
    public PasswordChangeService(AuthUserRepository users, ApiSessionRepository sessions, AuditLogRepository audit) {
        this.users=users; this.sessions=sessions; this.audit=audit;
    }
    public void change(ApiUser principal, char[] current, char[] next, char[] confirm, String client) {
        boolean acquired=false;
        try {
            acquired=hashing.tryAcquire(5,TimeUnit.SECONDS);
            if (!acquired) throw new ApiException(ErrorCode.TOO_MANY_REQUESTS);
            var user=users.findCredentials(principal.userId()).orElseThrow(() -> new ApiException(ErrorCode.SESSION_REVOKED));
            if (!user.active()) throw new ApiException(ErrorCode.SESSION_REVOKED);
            if (!PasswordHasher.verify(current,user.passwordHash())) {
                audit.logQuietly(user.userId(),AuditLogRepository.LOGIN_FAILED,"Users",String.valueOf(user.userId()),
                        "Password change credential rejected",client);
                throw new FieldValidationException("currentPassword","كلمة المرور الحالية غير صحيحة.");
            }
            try { CredentialPolicy.validateNewPassword(next,confirm,user.username()); }
            catch (IllegalArgumentException e) { throw new FieldValidationException("newPassword",e.getMessage()); }
            if (Arrays.equals(current,next))
                throw new FieldValidationException("newPassword","كلمة المرور الجديدة يجب أن تختلف عن الحالية.");
            if (!users.changePassword(user.userId(),user.passwordHash(),PasswordHasher.hash(next)))
                throw new ApiException(ErrorCode.SESSION_REVOKED);
            audit.logQuietly(user.userId(),"PASSWORD_CHANGED","Users",String.valueOf(user.userId()),
                    "API self-service password changed; sign in again",client);
            sessions.revokeAll(user.userId(),"PASSWORD_CHANGED");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
        } finally {
            if (acquired) hashing.release();
            PasswordHasher.wipe(current); PasswordHasher.wipe(next); PasswordHasher.wipe(confirm);
        }
    }
}
