package com.almahwar.api;

import com.almahwar.api.admin.*;
import com.almahwar.api.auth.AuthUserRepository.UserState;
import com.almahwar.api.security.*;
import com.almahwar.api.support.ApiWebTestBase;
import com.almahwar.api.manager.*;
import com.almahwar.api.web.*;
import com.almahwar.model.*;
import com.almahwar.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc(print=org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
class AdminApiTest extends ApiWebTestBase {
    @MockitoBean AdminReadRepository reads;
    @MockitoBean AuditReadRepository audits;
    @MockitoBean AdminSessionRepository mutations;
    @MockitoBean UserService core;
    @Autowired AdminService service;
    @Autowired AuditQueryService auditService;
    static final String ROOT="/api/v1/manager/admin";
    static final String PASSWORD="Synthetic#Test2026";
    static final AdminDtos.User SAFE=new AdminDtos.User(2,"user","أحمد",null,null,"CASHIER","كاشير",true,true,null,null);
    static final UserAccount ACCOUNT=new UserAccount(2,"user","أحمد",null,null,"CASHIER","كاشير",true,false,0,true,null,null,null);
    record Route(String method,String path,String body) { }
    static final List<Route> ROUTES=List.of(new Route("GET",ROOT+"/users",null),new Route("GET",ROOT+"/users/2",null),
            new Route("POST",ROOT+"/users","{\"username\":\"user\",\"fullName\":\"أحمد\",\"roleCode\":\"CASHIER\",\"password\":\""+PASSWORD+"\",\"confirmPassword\":\""+PASSWORD+"\"}"),
            new Route("PUT",ROOT+"/users/2","{\"fullName\":\"أحمد\",\"roleCode\":\"CASHIER\",\"active\":true}"),
            new Route("POST",ROOT+"/users/2/disable",null),new Route("POST",ROOT+"/users/2/enable",null),
            new Route("POST",ROOT+"/users/2/reset-password","{\"password\":\""+PASSWORD+"\",\"confirmPassword\":\""+PASSWORD+"\"}"),
            new Route("GET",ROOT+"/roles",null),new Route("GET",ROOT+"/permissions",null),new Route("GET","/api/v1/manager/audit",null));
    @BeforeEach void data() {
        when(reads.detail(anyInt())).thenReturn(Optional.of(SAFE));
        when(reads.users(any(),any(),any())).thenReturn(PageResponse.of(List.of(SAFE),new PageQuery(0,20),1));
        when(core.roles()).thenReturn(Map.of("ADMIN","مدير","CASHIER","كاشير"));
        when(core.permissionMatrix()).thenReturn(List.of(new UserAccount.PermissionRow(Permission.USERS_VIEW,"الإدارة",Set.of("ADMIN"))));
        when(core.create(any(),any(),any())).thenReturn(ACCOUNT);
        when(core.update(any())).thenReturn(ACCOUNT);
        when(core.setActive(anyInt(),anyBoolean())).thenReturn(ACCOUNT);
        when(core.resetPassword(anyInt(),any(),any(),anyBoolean())).thenReturn(ACCOUNT);
        when(mutations.mutate(anyInt(),anyBoolean(),any())).thenAnswer(i->((Supplier<?>)i.getArgument(2)).get());
        when(audits.today()).thenReturn(LocalDate.of(2026,10,7));
        when(audits.audit(any(),any(),any(),any(),any())).thenReturn(PageResponse.of(List.of(new AdminDtos.Audit(1,null,1,"admin","أحمد","USER_CREATED","Users")),new PageQuery(0,20),1));
    }
    static Stream<Arguments> matrix() {
        return ROUTES.stream().flatMap(r->Stream.of("anonymous","ADMIN","ACCOUNTANT","CASHIER","STOREKEEPER","restricted","disabled","revoked","downgraded").map(s->Arguments.of(r,s)));
    }
    @ParameterizedTest @MethodSource("matrix") void everyRouteEnforcesLiveSecurityAndNoStore(Route route,String identity) throws Exception {
        UserState user=switch(identity) { case "ACCOUNTANT"->ACCOUNTANT;case "CASHIER"->CASHIER;case "STOREKEEPER"->STOREKEEPER;case "restricted"->MUST_CHANGE;case "disabled"->DISABLED;default->ADMIN; };
        if(identity.equals("revoked")) when(sessionRepository.live(any(),anyInt(),anyString(),nullable(String.class),anyBoolean())).thenReturn(false);
        if(identity.equals("downgraded")) when(authUsers.findState(1)).thenReturn(Optional.of(state(1,"admin","CASHIER","كاشير",true,false)));
        var request=request(HttpMethod.valueOf(route.method()),route.path()).contentType("application/json");
        if(route.body()!=null) request.content(route.body());
        if(!identity.equals("anonymous")) request.header("Authorization",bearer(user));
        int expected=identity.equals("ADMIN")?(route.path().equals(ROOT+"/users") && route.method().equals("POST")?201:200)
                :Set.of("anonymous","disabled","revoked").contains(identity)?401:403;
        var result=mvc.perform(request).andExpect(status().is(expected));
        if(identity.equals("ADMIN")) result.andExpect(header().string("Cache-Control","no-store"));
        else verifyNoInteractions(reads,audits,core,mutations);
    }
    @Test void safeDtoAndMetadataExcludeSecurityInternals() throws Exception {
        String result=mvc.perform(get(ROOT+"/users/2").header("Authorization",bearer(ADMIN))).andReturn().getResponse().getContentAsString();
        assertThat(result).contains("أحمد","mustChangePassword").doesNotContain("hash","salt","fingerprint","locked","failed","passwordChanged",PASSWORD);
        mvc.perform(get(ROOT+"/permissions").header("Authorization",bearer(ADMIN))).andExpect(jsonPath("$[0].description").value(Permission.USERS_VIEW.getLabelAr()));
    }
    static Stream<Route> routes() { return ROUTES.stream(); }
    @ParameterizedTest @MethodSource("routes") void forgedSpringAuthoritiesCannotOpenAnyAdministrationRoute(Route route) throws Exception {
        var principal=new ApiUser(2,"cashier","أحمد","CASHIER","كاشير",false,Set.of(Permission.values()),null,TEST_SID);
        var request=request(HttpMethod.valueOf(route.method()),route.path()).contentType("application/json")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(new ApiAuthenticationToken(principal)));
        if(route.body()!=null) request.content(route.body());
        mvc.perform(request).andExpect(status().isForbidden());verifyNoInteractions(reads,audits,core,mutations);
    }
    @ParameterizedTest @ValueSource(strings={"/users?page=-1","/users?size=101","/users?sort=password","/users?role=MANAGER","/users/0"})
    void invalidReadInputsAre400(String path) throws Exception { mvc.perform(get(ROOT+path).header("Authorization",bearer(ADMIN))).andExpect(status().isBadRequest()); }
    @Test void missingUser404AndCoreValidationDoesNotEchoInput() throws Exception {
        when(reads.detail(99)).thenReturn(Optional.empty());
        mvc.perform(get(ROOT+"/users/99").header("Authorization",bearer(ADMIN))).andExpect(status().isNotFound());
        when(core.setActive(2,false)).thenThrow(new ValidationException("active","internal secret "+PASSWORD));
        String response=mvc.perform(post(ROOT+"/users/2/disable").header("Authorization",bearer(ADMIN)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("active")).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(PASSWORD,"internal secret");
    }
    @Test void mutationValidationAndNoDeletion() throws Exception {
        mvc.perform(put(ROOT+"/users/2").header("Authorization",bearer(ADMIN)).contentType("application/json").content("{\"fullName\":\"أحمد\",\"roleCode\":\"CASHIER\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(ROOT+"/users/2/reset-password").header("Authorization",bearer(ADMIN)).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete(ROOT+"/users/2").header("Authorization",bearer(ADMIN))).andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(mutations);
    }
    @Test void cleanupFailureSafe503AndNoCredentialInError() throws Exception {
        doThrow(new org.springframework.dao.DataAccessResourceFailureException(PASSWORD)).when(mutations).mutate(anyInt(),anyBoolean(),any());
        String body=mvc.perform(post(ROOT+"/users/2/disable").header("Authorization",bearer(ADMIN)))
                .andExpect(status().isServiceUnavailable()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(PASSWORD,"SQLException","stackTrace");
    }
    @Test void directServiceBoundariesRejectForgedPrincipalPermissions() {
        var principal=new ApiUser(2,"cashier","أحمد","CASHIER","كاشير",false,Set.of(Permission.values()),null,TEST_SID);
        SecurityContextHolder.getContext().setAuthentication(new ApiAuthenticationToken(principal));
        try {
            List<Runnable> operations=List.of(()->service.users(new ManagerPage(null,null,null,null),null,null),()->service.detail(2),()->service.roles(),()->service.permissions(),
                    ()->service.create(new AdminDtos.Create("x","أحمد",null,null,"ADMIN",PASSWORD,PASSWORD)),
                    ()->service.edit(2,new AdminDtos.Edit("أحمد",null,null,"ADMIN",true)),()->service.active(2,false),()->service.active(2,true),
                    ()->service.reset(2,new AdminDtos.Reset(PASSWORD,PASSWORD)),()->auditService.audit(null,new ManagerPage(null,null,null,null),null,null,null));
            operations.forEach(operation->assertThatThrownBy(operation::run).isInstanceOf(AccessDeniedException.class));
            verifyNoInteractions(reads,audits,core,mutations);
        } finally { SecurityContextHolder.clearContext(); }
    }
    @Test void coreReceivesTemporaryPasswordFlagsAndArraysAreWiped() throws Exception {
        char[][] received=new char[2][];
        when(core.create(any(),any(),any())).thenAnswer(i->{ assertThat(((UserAccount.NewUser)i.getArgument(0)).mustChangePassword()).isTrue();received[0]=i.getArgument(1);received[1]=i.getArgument(2);return ACCOUNT; });
        var route=ROUTES.get(2);
        mvc.perform(post(route.path()).header("Authorization",bearer(ADMIN)).contentType("application/json").content(route.body())).andExpect(status().isCreated());
        assertThat(received[0]).containsOnly('\0');assertThat(received[1]).containsOnly('\0');
        assertThat(new AdminDtos.Reset(PASSWORD,PASSWORD).toString()).doesNotContain(PASSWORD);
        assertThat(new AdminDtos.Create("x","x",null,null,"ADMIN",PASSWORD,PASSWORD).toString()).doesNotContain(PASSWORD);
    }
}
