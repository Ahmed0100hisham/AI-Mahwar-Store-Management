package com.almahwar.api;

import com.almahwar.api.auth.AuthService;
import com.almahwar.api.auth.AuthUserRepository;
import com.almahwar.api.auth.PasswordChangeService;
import com.almahwar.api.auth.dto.LoginResponse;
import com.almahwar.api.config.ApiProperties;
import com.almahwar.api.security.ApiUser;
import com.almahwar.api.security.TokenService;
import com.almahwar.api.session.*;
import com.almahwar.api.support.*;
import com.almahwar.service.RolePermissions;
import com.almahwar.util.PasswordHasher;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** All security mutations use two unique disposable catalogs; concurrency uses separate SQL connections/repos. */
@EnabledIfSystemProperty(named="almahwar.it",matches="true")
@SpringBootTest
@org.springframework.test.context.ActiveProfiles("dev")
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class SessionSqlServerIntegrationTest {
    private static final String PASSWORD="كلمة#آمنة2026";
    private static final String NEXT="كلمة#جديدة2027";
    private static final String HASH=PasswordHasher.hash(PASSWORD.toCharArray());
    private static final tools.jackson.databind.json.JsonMapper JSON=tools.jackson.databind.json.JsonMapper.builder().build();
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired AuthUserRepository users;
    @Autowired ApiSessionRepository repository;
    @Autowired SessionService sessions;
    @Autowired PasswordChangeService passwords;
    @Autowired ApiSessionSchemaRepository schema;
    @Autowired ApiProperties properties;
    @Autowired TokenService tokenIssuer;
    @Autowired @Qualifier("sessionDataSource") DataSource sessionDataSource;
    private String username;
    private int userId;

    @DynamicPropertySource static void database(DynamicPropertyRegistry r) throws Exception {
        TemporaryDatabase.create();
        TemporaryApiDatabase.create();
        TemporaryApiDatabase.properties(r);
        r.add("almahwar.db.host",TemporaryDatabase::host);
        r.add("almahwar.db.port",TemporaryDatabase::port);
        r.add("almahwar.db.name",()->TemporaryDatabase.NAME);
        r.add("almahwar.db.user",TemporaryDatabase::user);
        r.add("almahwar.db.password",TemporaryDatabase::password);
        r.add("almahwar.db.trust-server-certificate",()->String.valueOf(TemporaryDatabase.trustServerCertificate()));
        r.add("almahwar.api.jwt.secret",()->ApiWebTestBase.TEST_JWT_SECRET);
        r.add("almahwar.api.health.ready-cache",()->"0s");
    }
    @AfterAll static void cleanup() throws Exception {
        try { TemporaryApiDatabase.drop(); } finally { TemporaryDatabase.drop(); }
        try(var c=TemporaryDatabase.connect("master");var ps=c.prepareStatement("SELECT DB_ID(?), DB_ID(?)")) {
            ps.setString(1,TemporaryDatabase.NAME);ps.setString(2,TemporaryApiDatabase.NAME);
            try(var rs=ps.executeQuery()) { assertThat(rs.next()).isTrue();assertThat(rs.getObject(1)).isNull();assertThat(rs.getObject(2)).isNull(); }
        }
    }
    @BeforeEach void user() throws Exception {
        username="phase2_"+UUID.randomUUID().toString().replace("-","").substring(0,20);
        SqlServerIntegrationTest.user(username,HASH,"CASHIER",true,false,null);
        userId=((Number)TemporaryDatabase.queryOne("SELECT user_id FROM dbo.Users WHERE username=?",username)).intValue();
    }
    private LoginResponse login() { return auth.login(username,PASSWORD.toCharArray(),"integration","هاتف أحمد"); }
    private ApiUser principal(LoginResponse response) {
        var u=users.findState(userId).orElseThrow();
        return new ApiUser(userId,u.username(),u.fullName(),u.roleCode(),u.roleName(),u.mustChangePassword(),
                RolePermissions.forRole(u.roleCode()),null,response.sid());
    }
    private int count(String sql,Object... p) throws Exception { return ((Number)TemporaryApiDatabase.query(sql,p)).intValue(); }
    private void accessFails(LoginResponse r) throws Exception {
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+r.accessToken()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
    }
    private void refreshFails(String raw) throws Exception {
        var response=mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("refreshToken",raw))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(raw,"REUSE","hash","sid","SQL");
    }

    @Test void loginPersistsOnlyHashesWithCorrectDatabaseClockLifetimes() throws Exception {
        var r=login();
        assertThat(r.refreshToken()).matches("amr_[A-Za-z0-9_-]{43}");
        assertThat(r.sid()).isNotNull();
        assertThat(r.expiresIn()).isEqualTo(900);
        assertThat(TemporaryApiDatabase.query("SELECT token_hash FROM dbo.api_refresh_tokens WHERE sid=?",r.sid().toString()))
                .isEqualTo(RefreshTokens.hash(r.refreshToken()));
        assertThat(count("SELECT DATEDIFF(SECOND,created_at,idle_expires_at) FROM dbo.api_sessions WHERE sid=?",r.sid().toString()))
                .isEqualTo(8*3600);
        assertThat(count("SELECT DATEDIFF(SECOND,created_at,absolute_expires_at) FROM dbo.api_sessions WHERE sid=?",r.sid().toString()))
                .isEqualTo(7*86400);
        assertThat(TemporaryApiDatabase.query("SELECT device_label FROM dbo.api_sessions WHERE sid=?",r.sid().toString())).isEqualTo("هاتف أحمد");
        assertThat(count("SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='api_refresh_tokens' AND DATA_TYPE IN ('varchar','nvarchar','text','ntext')"))
                .isZero();
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+r.accessToken())).andExpect(status().isOk());
    }

    @Test void rotationKeepsHistoryAndReplayRevokesReplacementAndAccess(CapturedOutput output) throws Exception {
        var first=login();
        var next=sessions.refresh(first.refreshToken(),"integration");
        assertThat(next.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(next.sid()).isEqualTo(first.sid());
        assertThat(next.absoluteExpiresAt()).isEqualTo(first.absoluteExpiresAt());
        assertThat(count("SELECT COUNT(*) FROM dbo.api_refresh_tokens WHERE sid=?",first.sid().toString())).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM dbo.api_refresh_tokens WHERE sid=? AND consumed_at IS NOT NULL",first.sid().toString())).isEqualTo(1);
        refreshFails(first.refreshToken());
        refreshFails(next.refreshToken());
        accessFails(first); accessFails(next);
        assertThat(TemporaryDatabase.queryOne("SELECT COUNT(*) FROM dbo.Audit_Log WHERE user_id=? AND action='API_REFRESH_REUSE'",userId))
                .isEqualTo(1);
        assertThat(output.getAll()).contains("API_REFRESH_REUSE").doesNotContain(first.refreshToken(),next.refreshToken(),
                first.accessToken(),next.accessToken(),HASH,PASSWORD,properties.jwt().secret());
    }

    @Test void replayOfAnEarlyTokenStillRevokesAfterManyRotations() throws Exception {
        var first=login(); var current=first;
        for(int i=0;i<8;i++) current=sessions.refresh(current.refreshToken(),"integration");
        assertThat(count("SELECT COUNT(*) FROM dbo.api_refresh_tokens WHERE sid=?",first.sid().toString())).isEqualTo(9);
        refreshFails(first.refreshToken()); refreshFails(current.refreshToken());
    }

    @ParameterizedTest @ValueSource(strings={"idle","absolute","revoked","disabled","pwv","hash","deleted","restricted","role"})
    void refreshRejectsEveryInvalidCurrentState(String state) throws Exception {
        var r=login();
        switch(state) {
            case "idle" -> TemporaryApiDatabase.exec("UPDATE dbo.api_sessions SET idle_expires_at=DATEADD(SECOND,-1,SYSUTCDATETIME()) WHERE sid=?",r.sid().toString());
            case "absolute" -> TemporaryApiDatabase.exec("UPDATE dbo.api_sessions SET idle_expires_at=DATEADD(SECOND,-2,SYSUTCDATETIME()),absolute_expires_at=DATEADD(SECOND,-1,SYSUTCDATETIME()) WHERE sid=?",r.sid().toString());
            case "revoked" -> repository.revoke(r.sid(),userId,"TEST");
            case "disabled" -> TemporaryDatabase.exec("UPDATE dbo.Users SET is_active=0 WHERE user_id=?",userId);
            case "pwv" -> TemporaryDatabase.exec("UPDATE dbo.Users SET password_changed_at=DATEADD(SECOND,1,password_changed_at) WHERE user_id=?",userId);
            case "hash" -> TemporaryDatabase.exec("UPDATE dbo.Users SET password_hash=? WHERE user_id=?",PasswordHasher.hash(NEXT.toCharArray()),userId);
            case "deleted" -> {
                TemporaryDatabase.exec("DELETE dbo.Audit_Log WHERE user_id=?",userId);
                TemporaryDatabase.exec("DELETE dbo.Users WHERE user_id=?",userId);
            }
            case "restricted" -> TemporaryDatabase.exec("UPDATE dbo.Users SET must_change_password=1 WHERE user_id=?",userId);
            case "role" -> TemporaryDatabase.exec("UPDATE dbo.Users SET role_id=(SELECT role_id FROM dbo.Roles WHERE role_code='MANAGER') WHERE user_id=?",userId);
        }
        refreshFails(r.refreshToken()); accessFails(r);
    }

    @Test void refreshCapsIdleExpiryAtOriginalAbsoluteLimit() throws Exception {
        var r=login();
        TemporaryApiDatabase.exec("UPDATE dbo.api_sessions SET idle_expires_at=DATEADD(HOUR,1,SYSUTCDATETIME()),absolute_expires_at=DATEADD(HOUR,2,SYSUTCDATETIME()) WHERE sid=?",r.sid().toString());
        var next=sessions.refresh(r.refreshToken(),"integration");
        assertThat(next.refreshExpiresAt()).isEqualTo(next.absoluteExpiresAt());
        assertThat(Duration.between(java.time.Instant.now(),next.absoluteExpiresAt()).toHours()).isLessThanOrEqualTo(2);
    }

    @Test void logoutIsIdempotentAndEndsCurrentSessionOnly() throws Exception {
        var current=login();var other=login();var who=principal(current);
        mvc.perform(post("/api/v1/auth/logout").header("Authorization","Bearer "+current.accessToken()))
                .andExpect(status().isNoContent());
        sessions.logout(who,"integration"); // persistence action remains idempotent
        mvc.perform(post("/api/v1/auth/logout").header("Authorization","Bearer "+current.accessToken()))
                .andExpect(status().isUnauthorized()); // revoked bearer can no longer authenticate
        refreshFails(current.refreshToken()); accessFails(current);
        assertThat(sessions.refresh(other.refreshToken(),"integration").sid()).isEqualTo(other.sid());
    }

    @Test void logoutAllRevokesEveryDeviceAndCompletesItsOwnRequest() throws Exception {
        var current=login();var other=login();
        String foreignName="foreign_"+UUID.randomUUID().toString().substring(0,8);
        SqlServerIntegrationTest.user(foreignName,HASH,"CASHIER",true,false,null);
        var foreign=auth.login(foreignName,PASSWORD.toCharArray(),"integration");
        mvc.perform(post("/api/v1/auth/logout-all").header("Authorization","Bearer "+current.accessToken()))
                .andExpect(status().isNoContent());
        accessFails(current);accessFails(other);refreshFails(other.refreshToken());
        assertThat(sessions.refresh(foreign.refreshToken(),"integration").sid()).isEqualTo(foreign.sid());
    }

    @Test void listAndIndividualRevocationHaveNoIdorOrSensitiveFields() throws Exception {
        var current=login();var own=login();
        String foreignName="foreign_"+UUID.randomUUID().toString().substring(0,8);
        SqlServerIntegrationTest.user(foreignName,HASH,"CASHIER",true,false,null);
        var foreign=auth.login(foreignName,PASSWORD.toCharArray(),"integration");
        String body=mvc.perform(get("/api/v1/auth/sessions").header("Authorization","Bearer "+current.accessToken()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var rows=JSON.readTree(body);
        assertThat(rows.size()).isEqualTo(2);
        assertThat(body).contains(current.sid().toString(),own.sid().toString()).doesNotContain(foreign.sid().toString(),
                "token","hash","password",current.refreshToken(),current.accessToken());
        assertThat(sessions.list(principal(current)).stream().filter(SessionService.SessionView::current).count()).isEqualTo(1);
        for(UUID sid:List.of(foreign.sid(),UUID.randomUUID(),own.sid()))
            mvc.perform(delete("/api/v1/auth/sessions/"+sid).header("Authorization","Bearer "+current.accessToken()))
                    .andExpect(status().isNoContent());
        refreshFails(own.refreshToken());
        assertThat(sessions.refresh(foreign.refreshToken(),"integration").sid()).isEqualTo(foreign.sid());
    }

    @Test void requiredChangeHasNoRefreshAndClearsFlagWithDesktopCompatibleHash() throws Exception {
        TemporaryDatabase.exec("UPDATE dbo.Users SET must_change_password=1 WHERE user_id=?",userId);
        var restricted=login();
        assertThat(restricted.mustChangePassword()).isTrue();
        assertThat(restricted.refreshToken()).isNull();
        assertThat(count("SELECT COUNT(*) FROM dbo.api_refresh_tokens WHERE sid=?",restricted.sid().toString())).isZero();
        mvc.perform(get("/api/v1/auth/sessions").header("Authorization","Bearer "+restricted.accessToken())).andExpect(status().isForbidden());
        changePasswordHttp(restricted,PASSWORD,NEXT,NEXT,204);
        accessFails(restricted);
        assertThat(TemporaryDatabase.queryOne("SELECT must_change_password FROM dbo.Users WHERE user_id=?",userId)).isEqualTo(false);
        String stored=(String)TemporaryDatabase.queryOne("SELECT password_hash FROM dbo.Users WHERE user_id=?",userId);
        assertThat(stored).startsWith("pbkdf2_sha256$600000$");
        assertThat(PasswordHasher.verify(NEXT.toCharArray(),stored)).isTrue();
        assertThat(PasswordHasher.verify(PASSWORD.toCharArray(),stored)).isFalse();
        assertThat(auth.login(username,NEXT.toCharArray(),"integration").mustChangePassword()).isFalse();
    }

    @Test void passwordChangeRejectsWrongCurrentWeakMismatchAndSamePasswords() throws Exception {
        var r=login();
        changePasswordHttp(r,"Wrong123",NEXT,NEXT,400);
        changePasswordHttp(r,PASSWORD,"short1","short1",400);
        changePasswordHttp(r,PASSWORD,NEXT,"Mismatch123",400);
        changePasswordHttp(r,PASSWORD,PASSWORD,PASSWORD,400);
        assertThat(TemporaryDatabase.queryOne("SELECT password_hash FROM dbo.Users WHERE user_id=?",userId)).isEqualTo(HASH);
        assertThat(sessions.refresh(r.refreshToken(),"integration").sid()).isEqualTo(r.sid());
    }

    @Test void passwordChangeRevokesAllDevicesAndPwvAdvancesEvenWithinSameSecond() throws Exception {
        var one=login();var two=login();
        Object old=TemporaryDatabase.queryOne("SELECT password_changed_at FROM dbo.Users WHERE user_id=?",userId);
        changePasswordHttp(one,PASSWORD,NEXT,NEXT,204);
        var changed=(java.sql.Timestamp)TemporaryDatabase.queryOne("SELECT password_changed_at FROM dbo.Users WHERE user_id=?",userId);
        assertThat(changed).isAfter((java.sql.Timestamp)old);
        accessFails(one);accessFails(two);refreshFails(two.refreshToken());
        assertThat(count("SELECT COUNT(*) FROM dbo.api_sessions WHERE user_id=? AND revoked_at IS NULL",userId)).isZero();
        assertThat(TemporaryDatabase.queryOne("SELECT COUNT(*) FROM dbo.Audit_Log WHERE user_id=? AND action='PASSWORD_CHANGED'",userId))
                .isEqualTo(1);
    }

    private void changePasswordHttp(LoginResponse r,String current,String next,String confirm,int status) throws Exception {
        mvc.perform(post("/api/v1/auth/change-password").header("Authorization","Bearer "+r.accessToken())
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(Map.of("currentPassword",current,
                        "newPassword",next,"confirmPassword",confirm,"username","someone-else"))))
                .andExpect(status().is(status));
    }

    @Test void sameTokenConcurrentRefreshHasOneWinnerAndCommittedReuseRevocation() throws Exception {
        var first=login();
        // Separate repository/transaction-manager instances share no Java lock.
        var secondRepo=new ApiSessionRepository(sessionDataSource,properties);
        var secondService=new SessionService(secondRepo,users,tokenIssuer,
                new com.almahwar.api.audit.AuditLogRepository(org.springframework.jdbc.core.simple.JdbcClient.create(
                        businessDataSource)));
        var results=race(()->attempt(()->sessions.refresh(first.refreshToken(),"race1")),
                ()->attempt(()->secondService.refresh(first.refreshToken(),"race2")));
        assertThat(results.stream().filter(LoginResponse.class::isInstance).count()).isEqualTo(1);
        assertThat(results.stream().filter(com.almahwar.api.error.ApiException.class::isInstance).count()).isEqualTo(1);
        LoginResponse winner=(LoginResponse)results.stream().filter(LoginResponse.class::isInstance).findFirst().orElseThrow();
        refreshFails(winner.refreshToken());accessFails(winner);
        assertThat(TemporaryApiDatabase.query("SELECT revocation_reason FROM dbo.api_sessions WHERE sid=?",first.sid().toString()))
                .isEqualTo("REFRESH_REUSE");
    }
    @Autowired @Qualifier("dataSource") DataSource businessDataSource;

    @ParameterizedTest @ValueSource(strings={"logout","logout-all","password"})
    void refreshRacingRevocationCannotLeaveUsableCredentials(String action) throws Exception {
        var first=login();var who=principal(first);
        var results=race(()->attempt(()->sessions.refresh(first.refreshToken(),"race")),()->attempt(()->{
            switch(action) {
                case "logout" -> sessions.logout(who,"race");
                case "logout-all" -> sessions.logoutAll(who,"race");
                case "password" -> passwords.change(who,PASSWORD.toCharArray(),NEXT.toCharArray(),NEXT.toCharArray(),"race");
            }
            return "revoked";
        }));
        assertThat(results).contains("revoked");
        for(Object result:results) if(result instanceof LoginResponse r) { refreshFails(r.refreshToken());accessFails(r); }
        accessFails(first);
        assertThat(count("SELECT COUNT(*) FROM dbo.api_sessions WHERE user_id=? AND revoked_at IS NULL",userId)).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"logout","logout-all","password"})
    void refreshActuallyWaitsForDatabaseUserLockThenSeesRevocation(String action) throws Exception {
        var first=login();
        var executor=Executors.newSingleThreadExecutor();
        try(var lock=TemporaryDatabase.connect(TemporaryApiDatabase.NAME)) {
            lock.setAutoCommit(false);
            try(var ps=lock.prepareStatement("DECLARE @started int; SELECT @started=COUNT(*) FROM sys.tables; DECLARE @r int; EXEC @r=sys.sp_getapplock @Resource=?,@LockMode='Exclusive',@LockOwner='Transaction',@LockTimeout=10000; SELECT @r")) {
                ps.setString(1,"AlMahwarApi:user:"+userId);
                try(var rs=ps.executeQuery()) { assertThat(rs.next()).isTrue(); assertThat(rs.getInt(1)).isGreaterThanOrEqualTo(0); }
            }
            var entered=new CountDownLatch(1);
            var result=executor.submit(()->{entered.countDown();return attempt(()->sessions.refresh(first.refreshToken(),"race"));});
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->result.get(250,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            // Mutation occurs on the lock-owning SQL connection, just as logout/logout-all does in its transaction.
            if(action.equals("password")) {
                assertThat(users.changePassword(userId,HASH,PasswordHasher.hash(NEXT.toCharArray()))).isTrue();
                // No API revocation yet: pwv/fingerprint must independently reject refresh after a credential commit.
            } else try(var ps=lock.prepareStatement(action.equals("logout")
                    ? "UPDATE dbo.api_sessions SET revoked_at=SYSUTCDATETIME(),revocation_reason='LOGOUT' WHERE sid=?"
                    : "UPDATE dbo.api_sessions SET revoked_at=SYSUTCDATETIME(),revocation_reason='LOGOUT_ALL' WHERE user_id=?")) {
                ps.setObject(1,action.equals("logout")?first.sid().toString():userId);ps.executeUpdate();
            }
            lock.commit();
            assertThat(result.get(15,TimeUnit.SECONDS)).isInstanceOf(com.almahwar.api.error.ApiException.class);
            accessFails(first);
        } finally { executor.shutdownNow(); }
    }

    private static Object attempt(Supplier<Object> action) {
        try { return action.get(); } catch(com.almahwar.api.error.ApiException e) { return e; }
    }
    private static List<Object> race(Supplier<Object> first,Supplier<Object> second) throws Exception {
        var start=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->{start.await(10,TimeUnit.SECONDS);return first.get();});
            var b=pool.submit(()->{start.await(10,TimeUnit.SECONDS);return second.get();});
            return List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }

    @Test void baselineIsDeterministicIdempotentAndIncompatibleVersionFailsWithoutRepair() throws Exception {
        schema.initializeOrVerify();schema.initializeOrVerify();
        assertThat(TemporaryApiDatabase.query("SELECT version FROM dbo.api_schema_version WHERE id=1")).isEqualTo("1.0.0");
        assertThat(TemporaryDatabase.queryOne("SELECT schema_version FROM dbo.Schema_Info WHERE id=1")).isEqualTo("1.10.0");
        assertThat(TemporaryDatabase.queryOne("SELECT COUNT(*) FROM sys.tables WHERE name LIKE 'api[_]%' ")).isEqualTo(0);
        assertThat(count("SELECT COUNT(*) FROM sys.tables WHERE name='Users'")).isZero();
        TemporaryApiDatabase.exec("UPDATE dbo.api_schema_version SET version='9.0.0' WHERE id=1");
        try {
            assertThat(schema.compatible()).isFalse();
            assertThatThrownBy(schema::afterSingletonsInstantiated).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("incompatible");
            mvc.perform(get("/api/v1/health/ready")).andExpect(status().isServiceUnavailable());
            assertThat(TemporaryApiDatabase.query("SELECT version FROM dbo.api_schema_version WHERE id=1")).isEqualTo("9.0.0");
        } finally { TemporaryApiDatabase.exec("UPDATE dbo.api_schema_version SET version='1.0.0' WHERE id=1"); }
    }

    @Test void retentionDeletesOnlyTerminalFamiliesAndPreservesAllLiveHistory() throws Exception {
        var old=login();var live=login();var current=live;
        for(int i=0;i<3;i++) current=sessions.refresh(current.refreshToken(),"retention");
        TemporaryApiDatabase.exec("UPDATE dbo.api_sessions SET revoked_at=DATEADD(DAY,-31,SYSUTCDATETIME()) WHERE sid=?",old.sid().toString());
        TemporaryApiDatabase.exec(Files.readString(Path.of("database","cleanup_sessions.sql")));
        assertThat(count("SELECT COUNT(*) FROM dbo.api_sessions WHERE sid=?",old.sid().toString())).isZero();
        assertThat(count("SELECT COUNT(*) FROM dbo.api_refresh_tokens WHERE sid=?",old.sid().toString())).isZero();
        assertThat(count("SELECT COUNT(*) FROM dbo.api_refresh_tokens WHERE sid=?",live.sid().toString())).isEqualTo(4);
        assertThat(sessions.refresh(current.refreshToken(),"retention").sid()).isEqualTo(live.sid());
    }

    @Test void credentialCompareAndUpdateCannotOverwriteADesktopReset() throws Exception {
        String nextHash=PasswordHasher.hash(NEXT.toCharArray());
        TemporaryDatabase.exec("UPDATE dbo.Users SET password_hash=? WHERE user_id=?",nextHash,userId);
        assertThat(users.upgradePasswordHash(userId,HASH,PasswordHasher.hash(PASSWORD.toCharArray()))).isFalse();
        assertThat(users.changePassword(userId,HASH,PasswordHasher.hash(PASSWORD.toCharArray()))).isFalse();
        assertThat(TemporaryDatabase.queryOne("SELECT password_hash FROM dbo.Users WHERE user_id=?",userId)).isEqualTo(nextHash);
    }

    @Test void rotationUsesOneSqlTransactionAndRollsBackConsumptionOnInsertFailure() throws Exception {
        var first=login();
        byte[] original=RefreshTokens.hash(first.refreshToken());
        assertThatThrownBy(()->repository.rotate(original,original,s->{
            assertThat(org.springframework.jdbc.core.simple.JdbcClient.create(sessionDataSource)
                    .sql("SELECT @@TRANCOUNT").query(Integer.class).single()).isEqualTo(1);
            return true;
        })).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(count("SELECT COUNT(*) FROM dbo.api_refresh_tokens WHERE sid=? AND consumed_at IS NULL",first.sid().toString())).isEqualTo(1);
        assertThat(sessions.refresh(first.refreshToken(),"after-rollback").sid()).isEqualTo(first.sid());
        try(var con=sessionDataSource.getConnection();var st=con.createStatement();var rs=st.executeQuery("SELECT @@TRANCOUNT")) {
            assertThat(rs.next()).isTrue();assertThat(rs.getInt(1)).isZero();
        }
    }

    private DataSource unavailableSource() {
        String url="jdbc:sqlserver://"+TemporaryDatabase.host()+":"+TemporaryDatabase.port()
                +";databaseName=AlMahwarApiMissing_"+UUID.randomUUID().toString().replace("-","")
                +";encrypt=true;trustServerCertificate="+TemporaryDatabase.trustServerCertificate()+";loginTimeout=1";
        return new org.springframework.jdbc.datasource.DriverManagerDataSource(url,TemporaryDatabase.user(),TemporaryDatabase.password());
    }

    @Test void unavailableBusinessDatabaseCannotRefreshFromStaleSessionState() throws Exception {
        var r=login();
        var offlineUsers=new AuthUserRepository(org.springframework.jdbc.core.simple.JdbcClient.create(unavailableSource()));
        var service=new SessionService(repository,offlineUsers,tokenIssuer,
                new com.almahwar.api.audit.AuditLogRepository(org.springframework.jdbc.core.simple.JdbcClient.create(businessDataSource)));
        assertThatThrownBy(()->service.refresh(r.refreshToken(),"outage"))
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        assertThat(count("SELECT COUNT(*) FROM dbo.api_refresh_tokens WHERE sid=? AND consumed_at IS NOT NULL",r.sid().toString())).isZero();
        assertThat(sessions.refresh(r.refreshToken(),"recovered").sid()).isEqualTo(r.sid());
    }

    @Test void unavailableApiDatabaseCannotCreateOrRotateUntrackedSessions() throws Exception {
        var r=login();
        var offline=new ApiSessionRepository(unavailableSource(),properties);
        assertThatThrownBy(()->offline.create(userId,"0",AuthUserRepository.fingerprint(HASH),null,false,RefreshTokens.hash(RefreshTokens.generate())))
                .isInstanceOf(org.springframework.transaction.CannotCreateTransactionException.class);
        assertThatThrownBy(()->offline.rotate(RefreshTokens.hash(r.refreshToken()),RefreshTokens.hash(RefreshTokens.generate()),s->true))
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        assertThatThrownBy(()->offline.live(r.sid(),userId,"0",AuthUserRepository.fingerprint(HASH),false))
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        assertThat(sessions.refresh(r.refreshToken(),"recovered").sid()).isEqualTo(r.sid());
    }

    @Test void credentialCommitSurvivesRevocationOutageAndOldSessionsStillFailClosed() throws Exception {
        var r=login();
        var service=new PasswordChangeService(users,new ApiSessionRepository(unavailableSource(),properties),
                new com.almahwar.api.audit.AuditLogRepository(org.springframework.jdbc.core.simple.JdbcClient.create(businessDataSource)));
        assertThatThrownBy(()->service.change(principal(r),PASSWORD.toCharArray(),NEXT.toCharArray(),NEXT.toCharArray(),"outage"))
                .isInstanceOf(org.springframework.transaction.CannotCreateTransactionException.class);
        String stored=(String)TemporaryDatabase.queryOne("SELECT password_hash FROM dbo.Users WHERE user_id=?",userId);
        assertThat(PasswordHasher.verify(NEXT.toCharArray(),stored)).isTrue();
        assertThat(count("SELECT COUNT(*) FROM dbo.api_sessions WHERE sid=? AND revoked_at IS NULL",r.sid().toString())).isEqualTo(1);
        accessFails(r);refreshFails(r.refreshToken());
        assertThat(auth.login(username,NEXT.toCharArray(),"recovered").refreshToken()).isNotBlank();
    }
}
