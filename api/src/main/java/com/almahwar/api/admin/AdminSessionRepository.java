package com.almahwar.api.admin;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Repository;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Supplier;

/** API-only orchestration. Each database commits independently; no business SQL or distributed transaction. */
@Repository
public class AdminSessionRepository {
    private final DataSource source;
    public AdminSessionRepository(@Qualifier("sessionDataSource") DataSource source) { this.source=source; }

    public <T> T mutate(int userId,boolean revokeBefore,Supplier<T> coreMutation) {
        try(Connection connection=source.getConnection()) {
            // Session ownership permits independent autocommit revocations while holding the Phase 2 user lease.
            connection.setAutoCommit(true);
            lock(connection,userId,false);
            try {
                if(revokeBefore) revoke(connection,userId);
                T result=coreMutation.get();
                revoke(connection,userId);
                return result;
            } finally {
                try { lock(connection,userId,true); }
                catch(SQLException releaseFailure) {
                    // Pooled close alone would retain a session-owned lock. Kill this physical connection.
                    try { connection.abort(Runnable::run); } catch(SQLException abortFailure) { releaseFailure.addSuppressed(abortFailure); }
                    if(source instanceof com.zaxxer.hikari.HikariDataSource pool) pool.evictConnection(connection);
                    throw releaseFailure;
                }
            }
        } catch(SQLException exception) {
            throw new DataAccessResourceFailureException("Administration session cleanup unavailable",exception);
        }
    }
    private static void lock(Connection connection,int userId,boolean release) throws SQLException {
        String sql=release
                ? "DECLARE @r int; EXEC @r=sys.sp_releaseapplock @Resource=?,@LockOwner='Session'; SELECT @r;"
                : "DECLARE @r int; EXEC @r=sys.sp_getapplock @Resource=?,@LockMode='Exclusive',@LockOwner='Session',@LockTimeout=10000; SELECT @r;";
        try(var statement=connection.prepareStatement(sql)) {
            statement.setString(1,"AlMahwarApi:user:"+userId);
            try(var result=statement.executeQuery()) {
                if(!result.next() || result.getInt(1)<0) throw new SQLException("Administration session lock unavailable","08000");
            }
        }
    }
    /** Same leased connection: a second connection taking the per-user lock would deadlock against this lease. */
    protected void revoke(Connection connection,int userId) throws SQLException {
        try(var statement=connection.prepareStatement("UPDATE dbo.api_sessions SET revoked_at=SYSUTCDATETIME(),revocation_reason='ADMIN_CHANGED' WHERE user_id=? AND revoked_at IS NULL")) {
            statement.setInt(1,userId);statement.executeUpdate();
        }
    }
}
