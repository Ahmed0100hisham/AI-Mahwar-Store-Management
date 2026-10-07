package com.almahwar.api.admin;

import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AdminSessionRepositoryTest {
    record Fixture(DataSource source,Connection connection,PreparedStatement lock,PreparedStatement revoke,ResultSet result) { }
    Fixture fixture() throws Exception {
        var source=mock(DataSource.class);var connection=mock(Connection.class);var lock=mock(PreparedStatement.class);var revoke=mock(PreparedStatement.class);var result=mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(startsWith("DECLARE"))).thenReturn(lock);
        when(connection.prepareStatement(startsWith("UPDATE"))).thenReturn(revoke);
        when(lock.executeQuery()).thenReturn(result);when(result.next()).thenReturn(true);when(result.getInt(1)).thenReturn(0);
        return new Fixture(source,connection,lock,revoke,result);
    }
    @Test void enableCommitsRevocationBeforeAndAfterCoreAndReleasesLease() throws Exception {
        var f=fixture();var repo=new AdminSessionRepository(f.source());
        String result=repo.mutate(2,true,()->{ try { verify(f.revoke()).executeUpdate(); } catch(SQLException e) { throw new AssertionError(e); }return "done"; });
        assertThat(result).isEqualTo("done");verify(f.revoke(),times(2)).executeUpdate();verify(f.lock(),times(2)).executeQuery();verify(f.connection()).close();verify(f.connection()).setAutoCommit(true);
    }
    @Test void preCleanupFailureDoesNotMutateCoreAndAlwaysReleasesLock() throws Exception {
        var f=fixture();when(f.revoke().executeUpdate()).thenThrow(new SQLException("synthetic outage"));
        var invoked=new AtomicBoolean();
        assertThatThrownBy(()->new AdminSessionRepository(f.source()).mutate(2,true,()->invoked.getAndSet(true))).isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        assertThat(invoked).isFalse();verify(f.lock(),times(2)).executeQuery();
    }
    @Test void postCleanupFailureAcknowledgesCoreWasAlreadyCommitted() throws Exception {
        var f=fixture();when(f.revoke().executeUpdate()).thenThrow(new SQLException("synthetic outage"));var invoked=new AtomicBoolean();
        assertThatThrownBy(()->new AdminSessionRepository(f.source()).mutate(2,false,()->invoked.getAndSet(true))).isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        assertThat(invoked).isTrue();verify(f.lock(),times(2)).executeQuery();
    }
    @Test void businessRejectionReleasesLeaseWithoutPostCleanup() throws Exception {
        var f=fixture();
        assertThatThrownBy(()->new AdminSessionRepository(f.source()).mutate(2,false,()->{throw new IllegalStateException("rejected");})).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(f.revoke());verify(f.lock(),times(2)).executeQuery();
    }
    @Test void failedReleaseAbortsPhysicalConnection() throws Exception {
        var f=fixture();when(f.lock().executeQuery()).thenReturn(f.result()).thenThrow(new SQLException("release failed"));
        assertThatThrownBy(()->new AdminSessionRepository(f.source()).mutate(2,false,()->"done")).isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        verify(f.connection()).abort(any());verify(f.connection()).close();
    }
    @Test void refusedLeaseNeverMutates() throws Exception {
        var f=fixture();when(f.result().getInt(1)).thenReturn(-1);var invoked=new AtomicBoolean();
        assertThatThrownBy(()->new AdminSessionRepository(f.source()).mutate(2,false,()->invoked.getAndSet(true))).isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        assertThat(invoked).isFalse();verifyNoInteractions(f.revoke());
    }
}
