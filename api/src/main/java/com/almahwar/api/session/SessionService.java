package com.almahwar.api.session;

import com.almahwar.api.audit.AuditLogRepository;
import com.almahwar.api.auth.AuthUserRepository;
import com.almahwar.api.auth.dto.CurrentUserResponse;
import com.almahwar.api.auth.dto.LoginResponse;
import com.almahwar.api.error.ApiException;
import com.almahwar.api.error.ErrorCode;
import com.almahwar.api.security.ApiUser;
import com.almahwar.api.security.TokenService;
import com.almahwar.service.RolePermissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class SessionService {
    private static final Logger LOG = LoggerFactory.getLogger(SessionService.class);
    private final ApiSessionRepository sessions;
    private final AuthUserRepository users;
    private final TokenService tokens;
    private final AuditLogRepository audit;

    public SessionService(ApiSessionRepository sessions, AuthUserRepository users, TokenService tokens,
                          AuditLogRepository audit) {
        this.sessions=sessions; this.users=users; this.tokens=tokens; this.audit=audit;
    }

    public LoginResponse login(AuthUserRepository.LoginRow login, String expectedHash, String label) {
        // Detect credential/user changes that happened while expensive PBKDF2 verification was running.
        var user = users.findState(login.userId()).orElseThrow(SessionService::invalid);
        if (!user.active() || !TokenService.passwordVersion(user.passwordChangedAt())
                .equals(TokenService.passwordVersion(login.passwordChangedAt()))
                || !AuthUserRepository.fingerprint(expectedHash).equals(user.credentialFingerprint())) throw invalid();
        if (RolePermissions.forRole(user.roleCode()).isEmpty()) throw invalid();
        String refresh = user.mustChangePassword() ? null : RefreshTokens.generate();
        var session = sessions.create(user.userId(), TokenService.passwordVersion(user.passwordChangedAt()),
                user.credentialFingerprint(),safeLabel(label), user.mustChangePassword(), refresh == null ? null : RefreshTokens.hash(refresh));
        return response(user, session, refresh);
    }

    public LoginResponse refresh(String raw, String client) {
        if (!RefreshTokens.validFormat(raw)) throw invalid();
        String replacement = RefreshTokens.generate();
        var result = sessions.rotate(RefreshTokens.hash(raw), RefreshTokens.hash(replacement), session -> {
            var state = users.findState(session.userId()).orElse(null);
            return state != null && state.active() && !state.mustChangePassword()
                    && !RolePermissions.forRole(state.roleCode()).isEmpty()
                    && session.credentialFingerprint().equals(state.credentialFingerprint())
                    && session.passwordVersion().equals(TokenService.passwordVersion(state.passwordChangedAt()));
        });
        // Reuse revocation COMMITTED before this failure is thrown: never roll it back with the HTTP error.
        if (result.status() == ApiSessionRepository.RotationStatus.REUSED) {
            LOG.warn("API_REFRESH_REUSE user={} sid={}", result.session().userId(), result.session().sid());
            event(result.session().userId(), "API_REFRESH_REUSE", result.session().sid(), client);
        }
        if (result.status() != ApiSessionRepository.RotationStatus.ROTATED) throw invalid();
        var state = users.findState(result.session().userId()).orElseThrow(SessionService::invalid);
        if (!state.active() || state.mustChangePassword() || !result.session().credentialFingerprint().equals(state.credentialFingerprint())
                || !result.session().passwordVersion().equals(TokenService.passwordVersion(state.passwordChangedAt())))
            throw invalid();
        return response(state, result.session(), replacement);
    }

    public void logout(ApiUser user, String client) {
        sessions.revoke(user.sessionId(), user.userId(), "LOGOUT");
        event(user.userId(), "LOGOUT", user.sessionId(), client);
    }
    public void logoutAll(ApiUser user, String client) {
        sessions.revokeAll(user.userId(), "LOGOUT_ALL");
        event(user.userId(), "API_LOGOUT_ALL", user.sessionId(), client);
    }
    public void revoke(ApiUser user, UUID sid, String client) {
        if (sessions.revoke(sid, user.userId(), "USER_REVOKED"))
            event(user.userId(), "API_SESSION_REVOKED", sid, client);
    }
    public record SessionView(UUID sid, Instant createdAt, Instant lastActivityAt, Instant idleExpiresAt,
                              Instant absoluteExpiresAt, boolean current, String deviceLabel, String status) { }
    public List<SessionView> list(ApiUser user) {
        return sessions.list(user.userId()).stream().map(s -> new SessionView(s.sid(),s.createdAt(),s.lastActivityAt(),
                s.idleExpiresAt(),s.absoluteExpiresAt(),s.sid().equals(user.sessionId()),s.deviceLabel(),
                s.revokedAt()!=null ? "REVOKED" : s.active() ? "ACTIVE" : "EXPIRED")).toList();
    }

    private LoginResponse response(AuthUserRepository.UserState user, ApiSessionRepository.Session s, String refresh) {
        var access = tokens.issue(user.userId(), user.passwordChangedAt(), s.sid());
        var principal = new ApiUser(user.userId(),user.username(),user.fullName(),user.roleCode(),user.roleName(),
                user.mustChangePassword(), user.mustChangePassword()? Set.of():RolePermissions.forRole(user.roleCode()),
                null,s.sid());
        return new LoginResponse(access.value(),"Bearer",access.expiresInSeconds(),access.expiresAt(),
                user.mustChangePassword(),CurrentUserResponse.of(principal),refresh,s.sid(),
                refresh==null?null:s.idleExpiresAt(),s.absoluteExpiresAt());
    }
    private void event(int user, String action, UUID sid, String client) {
        audit.logQuietly(user,action,null,sid.toString(),action,client);
    }
    public static String safeLabel(String label) {
        if (label==null || label.isBlank()) return null;
        if (label.length()>100 || label.codePoints().anyMatch(c -> Character.isISOControl(c)
                || Character.getType(c)==Character.FORMAT || c=='<' || c=='>'))
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        return label.strip();
    }
    private static ApiException invalid() { return new ApiException(ErrorCode.INVALID_CREDENTIALS); }
}
