package com.almahwar.api;

import com.almahwar.api.admin.*;
import com.almahwar.api.auth.*;
import com.almahwar.api.auth.AuthService;
import com.almahwar.api.auth.dto.LoginResponse;
import com.almahwar.api.security.*;
import com.almahwar.api.session.SessionService;
import com.almahwar.api.support.*;
import com.almahwar.service.*;
import com.almahwar.util.PasswordHasher;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.*;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real released core mutations; all catalogs and identities are disposable and use a temporary non-sa login. */
@EnabledIfSystemProperty(named="almahwar.it",matches="true")
@SpringBootTest @ActiveProfiles("dev") @AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@org.junit.jupiter.api.extension.ExtendWith(OutputCaptureExtension.class)
class AdminSqlServerIntegrationTest {
    private static final String PASSWORD="Phase4#Synthetic2026",NEXT="Phase4#Temporary2027",FINAL="Phase4#Chosen2028";
    private static final String HASH=PasswordHasher.hash(PASSWORD.toCharArray());
    private static final String ROOT="/api/v1/manager/admin";
    private static final tools.jackson.databind.json.JsonMapper JSON=tools.jackson.databind.json.JsonMapper.builder().build();
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired AuthUserRepository users;
    @Autowired SessionService sessions;
    @Autowired AdminService service;
    @Autowired AdminAccess access;
    @Autowired UserService core;
    @Autowired AdminReadRepository reads;
    @Autowired @Qualifier("sessionDataSource") DataSource sessionSource;
    String adminName,targetName;
    int admin,target;
    LoginResponse administrator;

    @DynamicPropertySource static void database(DynamicPropertyRegistry r) throws Exception {
        TemporaryDatabase.create();TemporaryApiDatabase.create();TemporaryApiDatabase.properties(r);
        r.add("almahwar.db.host",TemporaryDatabase::host);r.add("almahwar.db.port",TemporaryDatabase::port);
        r.add("almahwar.db.name",()->TemporaryDatabase.NAME);r.add("almahwar.db.user",TemporaryDatabase::user);
        r.add("almahwar.db.password",TemporaryDatabase::password);
        r.add("almahwar.db.trust-server-certificate",()->String.valueOf(TemporaryDatabase.trustServerCertificate()));
        r.add("almahwar.api.jwt.secret",()->ApiWebTestBase.TEST_JWT_SECRET);r.add("almahwar.api.health.ready-cache",()->"0s");
    }
    @AfterAll static void cleanup() throws Exception {
        try { TemporaryApiDatabase.drop(); } finally { TemporaryDatabase.drop(); }
        try(var c=TemporaryDatabase.connect("master");var ps=c.prepareStatement("SELECT DB_ID(?),DB_ID(?)")) {
            ps.setString(1,TemporaryDatabase.NAME);ps.setString(2,TemporaryApiDatabase.NAME);
            try(var rs=ps.executeQuery()) { assertThat(rs.next()).isTrue();assertThat(rs.getObject(1)).isNull();assertThat(rs.getObject(2)).isNull(); }
        }
    }
    static int number(Object n) { return ((Number)n).intValue(); }
    int seed(String role) throws Exception {
        String name="p4_"+UUID.randomUUID().toString().replace("-","").substring(0,20);
        SqlServerIntegrationTest.user(name,HASH,role,true,false,null);
        return number(TemporaryDatabase.queryOne("SELECT user_id FROM dbo.Users WHERE username=?",name));
    }
    String name(int id) throws Exception { return (String)TemporaryDatabase.queryOne("SELECT username FROM dbo.Users WHERE user_id=?",id); }
    @BeforeEach void fixtures() throws Exception {
        TemporaryDatabase.exec("UPDATE u SET is_active=0 FROM dbo.Users u JOIN dbo.Roles r ON r.role_id=u.role_id WHERE r.role_code='ADMIN'");
        admin=seed("ADMIN");target=seed("CASHIER");adminName=name(admin);targetName=name(target);
        administrator=auth.login(adminName,PASSWORD.toCharArray(),"phase4-test");
    }
    String body(Object value) { return JSON.writeValueAsString(value); }
    AdminDtos.Edit edit(String role,boolean active) { return new AdminDtos.Edit("أحمد المعدل",null,null,role,active); }
    MvcResult postAction(int id,String action,Object request) throws Exception {
        var builder=post(ROOT+"/users/"+id+"/"+action).header("Authorization","Bearer "+administrator.accessToken());
        if(request!=null) builder.contentType("application/json").content(body(request));
        return mvc.perform(builder).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn();
    }
    LoginResponse loginTarget() { return auth.login(targetName,PASSWORD.toCharArray(),"phase4-test","هاتف أحمد"); }
    void accessFails(LoginResponse response) throws Exception {
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+response.accessToken())).andExpect(status().isUnauthorized());
    }
    void refreshFails(LoginResponse response) throws Exception {
        mvc.perform(post("/api/v1/auth/refresh").contentType("application/json").content(body(Map.of("refreshToken",response.refreshToken())))).andExpect(status().isUnauthorized());
    }
    <T> T as(int actor,Supplier<T> work) {
        var state=users.findState(actor).orElseThrow();
        var principal=new ApiUser(actor,state.username(),state.fullName(),state.roleCode(),state.roleName(),state.mustChangePassword(),RolePermissions.forRole(state.roleCode()),null,null);
        SecurityContextHolder.getContext().setAuthentication(new ApiAuthenticationToken(principal));
        try { return work.get(); } finally { SecurityContextHolder.clearContext(); }
    }
    @Test void createUsesCoreNormalizationHashPolicyAuditAndSafeDto(CapturedOutput logs) throws Exception {
        String username="P4.New_"+UUID.randomUUID().toString().substring(0,8);
        var request=new AdminDtos.Create("  "+username+"  ","أحمد جديد",null,null,"ACCOUNTANT",NEXT,NEXT);
        String output=mvc.perform(post(ROOT+"/users").header("Authorization","Bearer "+administrator.accessToken()).contentType("application/json").content(body(request)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.mustChangePassword").value(true)).andReturn().getResponse().getContentAsString();
        int id=JSON.readTree(output).path("id").asInt();
        String hash=(String)TemporaryDatabase.queryOne("SELECT password_hash FROM dbo.Users WHERE user_id=?",id);
        assertThat(PasswordHasher.verify(NEXT.toCharArray(),hash)).isTrue();
        assertThat(TemporaryDatabase.queryOne("SELECT username FROM dbo.Users WHERE user_id=?",id)).isEqualTo(username.toLowerCase(Locale.ROOT));
        assertThat(TemporaryDatabase.queryOne("SELECT COUNT(*) FROM dbo.Audit_Log WHERE action='USER_CREATED' AND record_id=? AND machine_name='API'",String.valueOf(id))).isEqualTo(1);
        assertThat(output+logs.getAll()).doesNotContain(NEXT,hash,"password_hash","credentialFingerprint");
        var restricted=auth.login(username.toLowerCase(Locale.ROOT),NEXT.toCharArray(),"test");assertThat(restricted.mustChangePassword()).isTrue();assertThat(restricted.refreshToken()).isNull();
    }
    @ParameterizedTest @ValueSource(strings={"duplicate","username","weak","confirmation","role","name","email","phone"})
    void coreRejectsInvalidCreationWithoutPersisting(String invalid) throws Exception {
        String username=invalid.equals("duplicate")?targetName:invalid.equals("username")?"bad name":"p4valid_"+UUID.randomUUID().toString().substring(0,8);
        var request=new AdminDtos.Create(username,invalid.equals("name")?" ":"أحمد",invalid.equals("phone")?"invalid":null,invalid.equals("email")?"invalid":null,
                invalid.equals("role")?"MANAGER":"CASHIER",invalid.equals("weak")?"weak":NEXT,invalid.equals("confirmation")?FINAL:invalid.equals("weak")?"weak":NEXT);
        int before=number(TemporaryDatabase.queryOne("SELECT COUNT(*) FROM dbo.Users"));
        mvc.perform(post(ROOT+"/users").header("Authorization","Bearer "+administrator.accessToken()).contentType("application/json").content(body(request))).andExpect(status().isBadRequest());
        assertThat(TemporaryDatabase.queryOne("SELECT COUNT(*) FROM dbo.Users")).isEqualTo(before);
    }
    @Test void updateDelegatesReleasedProfileRoleAndActiveAudits() throws Exception {
        String result=mvc.perform(put(ROOT+"/users/"+target).header("Authorization","Bearer "+administrator.accessToken()).contentType("application/json").content(body(edit("ACCOUNTANT",false))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(result).contains("أحمد المعدل","ACCOUNTANT").doesNotContain(HASH,PASSWORD);
        assertThat(TemporaryDatabase.queryOne("SELECT username FROM dbo.Users WHERE user_id=?",target)).isEqualTo(targetName);
        for(String event:List.of("USER_UPDATED","USER_ROLE_CHANGED","USER_DISABLED"))
            assertThat(TemporaryDatabase.queryOne("SELECT COUNT(*) FROM dbo.Audit_Log WHERE action=? AND record_id=?",event,String.valueOf(target))).isEqualTo(1);
    }
    @Test void selfAndFinalAdministratorProtectionComesFromCore() throws Exception {
        mvc.perform(post(ROOT+"/users/"+admin+"/disable").header("Authorization","Bearer "+administrator.accessToken())).andExpect(status().isBadRequest());
        mvc.perform(put(ROOT+"/users/"+admin).header("Authorization","Bearer "+administrator.accessToken()).contentType("application/json").content(body(edit("CASHIER",false)))).andExpect(status().isBadRequest());
        assertThat(TemporaryDatabase.queryOne("SELECT COUNT(*) FROM dbo.Users u JOIN dbo.Roles r ON r.role_id=u.role_id WHERE u.is_active=1 AND r.role_code='ADMIN'")).isEqualTo(1);
        // Core also rejects demotion even if a previously authorized request reaches it after another admin disappears.
        as(admin,()->{ assertThatThrownBy(()->core.update(new com.almahwar.model.UserAccount.Changes(admin,"مدير",null,null,"CASHIER",true))).isInstanceOf(ValidationException.class);return null; });
        administrator=auth.login(adminName,PASSWORD.toCharArray(),"test");
        mvc.perform(put(ROOT+"/users/"+admin).header("Authorization","Bearer "+administrator.accessToken()).contentType("application/json").content(body(edit("ADMIN",true)))).andExpect(status().isOk());
    }
    @Test void disableThenEnableRequiresFreshLoginAndNeverResurrectsCredentials() throws Exception {
        var original=loginTarget();postAction(target,"disable",null);accessFails(original);refreshFails(original);
        assertThatThrownBy(this::loginTarget).isInstanceOf(com.almahwar.api.error.ApiException.class);
        postAction(target,"enable",null);accessFails(original);refreshFails(original);
        var fresh=loginTarget();assertThat(fresh.sid()).isNotEqualTo(original.sid());
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+fresh.accessToken())).andExpect(status().isOk());
        for(String event:List.of("USER_DISABLED","USER_ENABLED")) assertThat(TemporaryDatabase.queryOne("SELECT COUNT(*) FROM dbo.Audit_Log WHERE action=? AND record_id=?",event,String.valueOf(target))).isEqualTo(1);
    }
    @Test void resetInvalidatesBothCredentialsClearsLockAndRequiresOwnChange(CapturedOutput logs) throws Exception {
        var old=loginTarget();
        TemporaryDatabase.exec("UPDATE dbo.Users SET failed_login_attempts=4,locked_until=DATEADD(MINUTE,5,SYSDATETIME()) WHERE user_id=?",target);
        postAction(target,"reset-password",new AdminDtos.Reset(NEXT,NEXT));accessFails(old);refreshFails(old);
        assertThat(TemporaryDatabase.queryOne("SELECT failed_login_attempts FROM dbo.Users WHERE user_id=?",target)).isEqualTo(0);
        assertThat(TemporaryDatabase.queryOne("SELECT locked_until FROM dbo.Users WHERE user_id=?",target)).isNull();
        var restricted=auth.login(targetName,NEXT.toCharArray(),"test");assertThat(restricted.mustChangePassword()).isTrue();assertThat(restricted.refreshToken()).isNull();
        mvc.perform(get(ROOT+"/users").header("Authorization","Bearer "+restricted.accessToken())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/change-password").header("Authorization","Bearer "+restricted.accessToken()).contentType("application/json")
                .content(body(Map.of("currentPassword",NEXT,"newPassword",FINAL,"confirmPassword",FINAL)))).andExpect(status().isNoContent());
        assertThat(auth.login(targetName,FINAL.toCharArray(),"test").mustChangePassword()).isFalse();
        String audit=(String)TemporaryDatabase.queryOne("SELECT description FROM dbo.Audit_Log WHERE action='PASSWORD_RESET' AND record_id=?",String.valueOf(target));
        assertThat(audit+logs.getAll()).doesNotContain(PASSWORD,NEXT,FINAL,HASH,old.accessToken(),old.refreshToken());
        String allAudit=(String)TemporaryDatabase.queryOne("SELECT STRING_AGG(CONVERT(nvarchar(max),CONCAT(description,'|',old_values,'|',new_values,'|',machine_name)),NCHAR(10)) FROM dbo.Audit_Log WHERE table_name='Users' AND record_id=?",String.valueOf(target));
        String currentHash=(String)TemporaryDatabase.queryOne("SELECT password_hash FROM dbo.Users WHERE user_id=?",target);
        assertThat(allAudit).doesNotContain(PASSWORD,NEXT,FINAL,HASH,currentHash,old.accessToken(),old.refreshToken(),ApiWebTestBase.TEST_JWT_SECRET,TemporaryDatabase.password(),"Authorization");
    }
    @Test void repeatedResetWithinSameStoredSecondStillInvalidatesFingerprint() throws Exception {
        postAction(target,"reset-password",new AdminDtos.Reset(NEXT,NEXT));
        var restricted=auth.login(targetName,NEXT.toCharArray(),"test");
        var version=TemporaryDatabase.queryOne("SELECT password_changed_at FROM dbo.Users WHERE user_id=?",target);
        postAction(target,"reset-password",new AdminDtos.Reset(FINAL,FINAL));
        // Force equal second precision to deterministically exercise the Phase 2 fingerprint fallback.
        TemporaryDatabase.exec("UPDATE dbo.Users SET password_changed_at=? WHERE user_id=?",version,target);
        accessFails(restricted);assertThat(auth.login(targetName,FINAL.toCharArray(),"test").mustChangePassword()).isTrue();
    }
    @Test void selfResetUsesOwnPasswordFlowAndWeakResetIsRejected() throws Exception {
        for(int id:List.of(admin,target)) mvc.perform(post(ROOT+"/users/"+id+"/reset-password").header("Authorization","Bearer "+administrator.accessToken()).contentType("application/json")
                .content(body(new AdminDtos.Reset(id==admin?NEXT:"weak",id==admin?NEXT:"weak")))).andExpect(status().isBadRequest());
        assertThat(TemporaryDatabase.queryOne("SELECT password_hash FROM dbo.Users WHERE user_id=?",target)).isEqualTo(HASH);
    }
    @Test void liveRoleDowngradeImmediatelyUsesReleasedPermissions() throws Exception {
        int other=seed("ADMIN");var before=auth.login(name(other),PASSWORD.toCharArray(),"test");
        // A released-core change outside this API does not revoke API sessions: live permission loading still applies.
        as(admin,()->core.update(new com.almahwar.model.UserAccount.Changes(other,"مدير سابق",null,null,"CASHIER",true)));
        mvc.perform(get(ROOT+"/users").header("Authorization","Bearer "+before.accessToken())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/manager/dashboard").header("Authorization","Bearer "+before.accessToken())).andExpect(status().isOk());
    }
    @Test void auditPagingFiltersArabicAndLegacySecretRedaction() throws Exception {
        String sentinel="SYNTHETIC_SECRET_DO_NOT_EXPOSE";
        for(int i=0;i<3;i++) TemporaryDatabase.exec("INSERT dbo.Audit_Log(user_id,action,table_name,record_id,description,old_values,new_values,machine_name) VALUES(?,'USER_UPDATED','Users',?,?,?, ?,?)",
                admin,sentinel,sentinel,"{\"secret\":\""+sentinel+"\"}","{\"secret\":\""+sentinel+"\"}",sentinel);
        String url="/api/v1/manager/audit?userId="+admin+"&action=USER_UPDATED&category=Users&size=2";
        String first=mvc.perform(get(url).header("Authorization","Bearer "+administrator.accessToken())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.totalItems").value(3)).andReturn().getResponse().getContentAsString();
        String second=mvc.perform(get(url+"&page=1").header("Authorization","Bearer "+administrator.accessToken())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JSON.readTree(first).path("items").size()).isEqualTo(2);assertThat(JSON.readTree(second).path("items").size()).isEqualTo(1);
        assertThat(first+second).doesNotContain(sentinel,"old_values","new_values","description","machine_name").contains("مستخدم");
        TemporaryDatabase.exec("INSERT dbo.Audit_Log(action,table_name,description) VALUES('UNKNOWN_SECRET','unknown secret',?)",sentinel);
        String all=mvc.perform(get("/api/v1/manager/audit?size=100").header("Authorization","Bearer "+administrator.accessToken())).andReturn().getResponse().getContentAsString();
        assertThat(all).contains("OTHER").doesNotContain("UNKNOWN_SECRET","unknown secret",sentinel);
    }
    @ParameterizedTest @ValueSource(strings={"size=101","page=10001","sort=date,asc","sort=date;DELETE","q=secret","userId=-1","action=UNKNOWN","category=Users;DROP","from=2020-01-01&to=2026-01-01"})
    void auditRejectsInvalidUnboundedOrInjectedFilters(String query) throws Exception {
        mvc.perform(get("/api/v1/manager/audit?"+query).header("Authorization","Bearer "+administrator.accessToken())).andExpect(status().isBadRequest());
    }
    @Test void userPaginationLiteralSearchSortRoleAndMetadata() throws Exception {
        TemporaryDatabase.exec("UPDATE dbo.Users SET full_name=N'أحمد 100%_[ O''Reilly' WHERE user_id=?",target);
        for(String q:List.of("100%_[","O'Reilly","' OR 1=1 --")) {
            String output=mvc.perform(get(ROOT+"/users").param("q",q).param("sort","username,desc").param("size","1").header("Authorization","Bearer "+administrator.accessToken())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(JSON.readTree(output).path("totalItems").asInt()).isEqualTo(q.startsWith("' OR")?0:1);
        }
        mvc.perform(get(ROOT+"/users?role=CASHIER&active=true&size=1").header("Authorization","Bearer "+administrator.accessToken())).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].roleCode").value("CASHIER"));
        String roles=mvc.perform(get(ROOT+"/roles").header("Authorization","Bearer "+administrator.accessToken())).andReturn().getResponse().getContentAsString();
        assertThat(JSON.readTree(roles).size()).isEqualTo(4);assertThat(roles).doesNotContain("MANAGER");
        mvc.perform(get(ROOT+"/permissions").header("Authorization","Bearer "+administrator.accessToken())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
    }
    @ParameterizedTest @ValueSource(strings={"disable","demote"})
    void twoAdministratorsConcurrentCrossRemovalPreservesOne(String operation) throws Exception {
        int other=seed("ADMIN");var gate=new CyclicBarrier(2);var executor=Executors.newFixedThreadPool(2);
        // Capture both authorized principals before either core mutation starts, as concurrent HTTP requests do.
        var one=executor.submit(()->as(admin,()->race(gate,()->operation.equals("disable")?service.active(other,false):service.edit(other,edit("CASHIER",true)))));
        var two=executor.submit(()->as(other,()->race(gate,()->operation.equals("disable")?service.active(admin,false):service.edit(admin,edit("CASHIER",true)))));
        try {
            var results=List.of(one.get(25,TimeUnit.SECONDS),two.get(25,TimeUnit.SECONDS));
            assertThat(results).containsExactlyInAnyOrder("OK","REJECTED");
            assertThat(TemporaryDatabase.queryOne("SELECT COUNT(*) FROM dbo.Users u JOIN dbo.Roles r ON r.role_id=u.role_id WHERE u.is_active=1 AND r.role_code='ADMIN'")).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }
    String race(CyclicBarrier gate,Supplier<?> operation) {
        try { gate.await(10,TimeUnit.SECONDS);operation.get();return "OK"; }
        catch(ValidationException exception) { return "REJECTED"; }
        catch(Exception exception) { throw new RuntimeException(exception); }
    }
    @ParameterizedTest @ValueSource(strings={"disable-refresh","reset-refresh","reset-access","reset-reset","role-role","update-disable"})
    void concurrentAdministrationAndAuthenticationRemainFailClosed(String scenario) throws Exception {
        var before=loginTarget();var gate=new CyclicBarrier(2);var executor=Executors.newFixedThreadPool(2);
        var refreshed=new java.util.concurrent.atomic.AtomicReference<LoginResponse>();
        Callable<Object> first=()->as(admin,()->race(gate,()->scenario.startsWith("reset")?service.reset(target,new AdminDtos.Reset(NEXT,NEXT))
                :scenario.equals("role-role")?service.edit(target,edit("ACCOUNTANT",true)):scenario.equals("update-disable")?service.edit(target,edit("CASHIER",true)):service.active(target,false)));
        Callable<Object> second=()->as(admin,()->race(gate,()->{
            if(scenario.endsWith("refresh")) { try { var result=sessions.refresh(before.refreshToken(),"race");refreshed.set(result);return result; } catch(com.almahwar.api.error.ApiException e) { return "denied"; } }
            if(scenario.endsWith("access")) { try { int status=mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+before.accessToken())).andReturn().getResponse().getStatus();assertThat(status).isIn(200,401);return status; } catch(Exception e) {throw new RuntimeException(e);} }
            if(scenario.equals("reset-reset")) return service.reset(target,new AdminDtos.Reset(FINAL,FINAL));
            if(scenario.equals("role-role")) return service.edit(target,edit("STOREKEEPER",true));
            return service.active(target,false);
        }));
        try { var a=executor.submit(first);var b=executor.submit(second);a.get(25,TimeUnit.SECONDS);b.get(25,TimeUnit.SECONDS);accessFails(before);refreshFails(before);
            if(refreshed.get()!=null) { accessFails(refreshed.get());refreshFails(refreshed.get()); }
            if(scenario.equals("role-role")) assertThat(users.findState(target).orElseThrow().roleCode()).isIn("ACCOUNTANT","STOREKEEPER");
            if(scenario.equals("update-disable")) {
                // PUT explicitly requests active=true. Core's last committed lifecycle action determines final state.
                String last=(String)TemporaryDatabase.queryOne("SELECT TOP(1) action FROM dbo.Audit_Log WHERE record_id=? AND action IN ('USER_ENABLED','USER_DISABLED') ORDER BY log_id DESC",String.valueOf(target));
                assertThat(TemporaryDatabase.queryOne("SELECT is_active FROM dbo.Users WHERE user_id=?",target)).isEqualTo(last.equals("USER_ENABLED"));
            }
        } finally { executor.shutdownNow(); }
    }
    @ParameterizedTest @ValueSource(strings={"disable","reset","enable"})
    void postCommitSessionOutageCannotResurrectCredentials(String operation) throws Exception {
        var before=loginTarget();
        if(operation.equals("enable")) TemporaryDatabase.exec("UPDATE dbo.Users SET is_active=0 WHERE user_id=?",target);
        AtomicInteger revocations=new AtomicInteger();
        var failing=new AdminSessionRepository(sessionSource) {
            @Override protected void revoke(Connection c,int id) throws SQLException {
                if(revocations.incrementAndGet()==(operation.equals("enable")?2:1)) throw new SQLException("Synthetic post-commit outage","08000");
                super.revoke(c,id);
            }
        };
        var isolated=new AdminService(access,core,reads,failing);
        as(admin,()->{ assertThatThrownBy(()->{ if(operation.equals("reset")) isolated.reset(target,new AdminDtos.Reset(NEXT,NEXT));else isolated.active(target,operation.equals("enable")); })
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);return null; });
        accessFails(before);refreshFails(before);
        if(operation.equals("enable")) assertThat(loginTarget().sid()).isNotEqualTo(before.sid());
        if(operation.equals("disable")) { postAction(target,"enable",null);accessFails(before);refreshFails(before); }
    }
    @Test void sqlSessionLeaseIsReleasedBeforePooledConnectionReuse() throws Exception {
        as(admin,()->service.active(target,false));
        // An independent physical connection cannot inherit a leaked pooled session-owned lease.
        try(var connection=TemporaryDatabase.connect(TemporaryApiDatabase.NAME);var statement=connection.prepareStatement("SELECT APPLOCK_TEST('public',?,'Exclusive','Session')")) {
            statement.setString(1,"AlMahwarApi:user:"+target);try(var result=statement.executeQuery()) { assertThat(result.next()).isTrue();assertThat(result.getInt(1)).isEqualTo(1); }
        }
        as(admin,()->service.active(target,true));
    }
    @Test void realHttpAdminLifecycleAuditErrorsAndOpenApi() throws Exception {
        try(var context=new SpringApplicationBuilder(AlMahwarApiApplication.class).logStartupInfo(false).run(TemporaryApiDatabase.withSessionArgs("--server.port=0",
                "--almahwar.db.host="+TemporaryDatabase.host(),"--almahwar.db.port="+TemporaryDatabase.port(),"--almahwar.db.name="+TemporaryDatabase.NAME,
                "--almahwar.db.user="+TemporaryDatabase.user(),"--almahwar.db.password="+TemporaryDatabase.password(),"--almahwar.db.trust-server-certificate="+TemporaryDatabase.trustServerCertificate(),
                "--almahwar.api.jwt.secret="+ApiWebTestBase.TEST_JWT_SECRET))) {
            var client=java.net.http.HttpClient.newHttpClient();int port=((org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext)context).getWebServer().getPort();
            String base="http://localhost:"+port;
            var adminToken=context.getBean(AuthService.class).login(adminName,PASSWORD.toCharArray(),"real-http");
            String bearer="Bearer "+adminToken.accessToken();
            String createdName="http_"+UUID.randomUUID().toString().substring(0,8);
            var created=http(client,base,ROOT+"/users","POST",new AdminDtos.Create(createdName,"أحمد عبر الشبكة",null,null,"CASHIER",NEXT,NEXT),bearer);
            assertThat(created.statusCode()).isEqualTo(201);assertThat(created.body()).doesNotContain(NEXT,"passwordHash");
            assertThat(http(client,base,ROOT+"/users/"+target,"PUT",edit("CASHIER",true),bearer).statusCode()).isEqualTo(200);
            for(String path:List.of(ROOT+"/users?size=1",ROOT+"/users/"+target,ROOT+"/roles",ROOT+"/permissions","/api/v1/manager/audit?size=1")) {
                var r=http(client,base,path,"GET",null,bearer);assertThat(r.statusCode()).isEqualTo(200);assertThat(r.headers().firstValue("Cache-Control")).contains("no-store");
            }
            var existing=context.getBean(AuthService.class).login(targetName,PASSWORD.toCharArray(),"real-http");
            assertThat(http(client,base,ROOT+"/users","GET",null,"Bearer "+existing.accessToken()).statusCode()).isEqualTo(403);
            assertThat(http(client,base,"/api/v1/auth/sessions","GET",null,"Bearer "+existing.accessToken()).statusCode()).isEqualTo(200);
            assertThat(http(client,base,ROOT+"/users","GET",null,null).statusCode()).isEqualTo(401);
            assertThat(http(client,base,ROOT+"/users/"+target+"/disable","POST",null,bearer).statusCode()).isEqualTo(200);
            assertThat(http(client,base,"/api/v1/auth/me","GET",null,"Bearer "+existing.accessToken()).statusCode()).isEqualTo(401);
            assertThat(http(client,base,ROOT+"/users/"+target+"/enable","POST",null,bearer).statusCode()).isEqualTo(200);
            assertThat(http(client,base,"/api/v1/auth/me","GET",null,"Bearer "+existing.accessToken()).statusCode()).isEqualTo(401);
            assertThat(http(client,base,ROOT+"/users/"+target+"/reset-password","POST",new AdminDtos.Reset(NEXT,NEXT),bearer).statusCode()).isEqualTo(200);
            var restricted=context.getBean(AuthService.class).login(targetName,NEXT.toCharArray(),"real-http");
            assertThat(http(client,base,ROOT+"/users","GET",null,"Bearer "+restricted.accessToken()).statusCode()).isEqualTo(403);
            var bad=http(client,base,"/api/v1/manager/audit?size=101","GET",null,bearer);assertThat(bad.statusCode()).isEqualTo(400);assertThat(bad.body()).contains("requestId").doesNotContain(HASH,NEXT);
            var docs=JSON.readTree(http(client,base,"/v3/api-docs","GET",null,null).body());
            assertThat(docs.path("paths").path(ROOT+"/users").has("post")).isTrue();assertThat(docs.path("paths").path(ROOT+"/users/{id}").has("delete")).isFalse();
            for(String path:List.of(ROOT+"/users",ROOT+"/users/{id}",ROOT+"/users/{id}/disable",ROOT+"/users/{id}/enable",ROOT+"/users/{id}/reset-password",ROOT+"/roles",ROOT+"/permissions","/api/v1/manager/audit"))
                assertThat(docs.path("paths").has(path)).isTrue();
            assertThat(docs.path("paths").path("/api/v1/manager/audit").path("get").path("security").toString()).contains("bearer");
        }
    }
    java.net.http.HttpResponse<String> http(java.net.http.HttpClient client,String base,String path,String method,Object payload,String bearer) throws Exception {
        var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+path));
        if(bearer!=null) request.header("Authorization",bearer);
        if(payload!=null) request.header("Content-Type","application/json");
        request.method(method,payload==null?java.net.http.HttpRequest.BodyPublishers.noBody():java.net.http.HttpRequest.BodyPublishers.ofString(body(payload)));
        return client.send(request.build(),java.net.http.HttpResponse.BodyHandlers.ofString());
    }
    @Test void schemaVersionsAndNonSecurityTablesRemainFrozen() throws Exception {
        assertThat(TemporaryDatabase.queryOne("SELECT schema_version FROM dbo.Schema_Info WHERE id=1")).isEqualTo("1.10.0");
        assertThat(TemporaryApiDatabase.query("SELECT version FROM dbo.api_schema_version WHERE id=1")).isEqualTo("1.0.0");
        for(String table:List.of("Sales","Purchases","Expenses","Stock_Movements","Cash_Transactions")) assertThat(TemporaryDatabase.queryOne("SELECT COUNT(*) FROM dbo."+table)).isEqualTo(0);
    }
}
