package com.almahwar.api;

import com.almahwar.api.manager.*;
import com.almahwar.api.support.ApiWebTestBase;
import com.almahwar.api.web.*;
import com.almahwar.api.error.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Stream;
import static com.almahwar.api.manager.ManagerDocumentResponses.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class ManagerDocumentApiTest extends ApiWebTestBase {
    @MockitoBean ManagerDocumentRepository repository;
    @MockitoBean ManagerAnalyticsRepository analytics;
    @MockitoBean ManagerQueryService reports;
    @Autowired ManagerDocumentService service;
    @Autowired org.springframework.security.oauth2.jwt.JwtEncoder encoder;
    static final LocalDate DAY=LocalDate.of(2026,10,7);
    static final List<String> PATHS=List.of("invoices","invoices/1","quotations","quotations/1","daily-summary");
    static String url(String path) { return "/api/v1/manager/"+path; }
    static <T> PageResponse<T> empty() { return PageResponse.of(List.of(),new PageQuery(0,20),0); }
    @BeforeEach void queries() {
        when(analytics.today()).thenReturn(DAY);
        when(repository.invoices(any(),any(),any(),anyBoolean(),anyBoolean())).thenReturn(empty());
        when(repository.invoice(eq(1),any(),any(),anyBoolean(),anyBoolean())).thenReturn(new InvoiceDetail(null,empty(),empty()));
        when(repository.quotations(any(),any(),any(),any(),anyBoolean())).thenReturn(empty());
        when(repository.quotation(eq(1),any(),any(),anyBoolean())).thenReturn(new QuotationDetail(null,empty()));
    }
    static Stream<String> paths() { return PATHS.stream(); }
    static Stream<Arguments> roles() { return Stream.of("ADMIN","ACCOUNTANT","CASHIER","STOREKEEPER").flatMap(r->PATHS.stream().map(p->Arguments.of(r,p))); }
    @ParameterizedTest @MethodSource("roles") void releasedPermissionsAtEveryRoute(String role,String path) throws Exception {
        var user=switch(role) {case "ADMIN"->ADMIN;case "ACCOUNTANT"->ACCOUNTANT;case "CASHIER"->CASHIER;default->STOREKEEPER;};
        mvc.perform(get(url(path)).header("Authorization",bearer(user))).andExpect(status().is(role.equals("STOREKEEPER")&&!path.equals("daily-summary")?403:200));
    }
    @ParameterizedTest @MethodSource("paths") void missingAndInvalidTokensFail(String path) throws Exception {
        mvc.perform(get(url(path))).andExpect(status().isUnauthorized());
        mvc.perform(get(url(path)).header("Authorization","Bearer invalid-test-token")).andExpect(status().isUnauthorized());
        verifyNoInteractions(repository,analytics,reports);
    }
    @ParameterizedTest @MethodSource("paths") void restrictedAndDisabledFail(String path) throws Exception {
        mvc.perform(get(url(path)).header("Authorization",bearer(MUST_CHANGE))).andExpect(status().isForbidden());
        mvc.perform(get(url(path)).header("Authorization",bearer(DISABLED))).andExpect(status().isUnauthorized());
        verifyNoInteractions(repository,analytics,reports);
    }
    @ParameterizedTest @MethodSource("paths") void expiredSignedTokensFailAtEveryRoute(String path) throws Exception {
        var claims=org.springframework.security.oauth2.jwt.JwtClaimsSet.builder().issuer("almahwar-api").audience(List.of("almahwar-mobile"))
                .subject("1").issuedAt(java.time.Instant.now().minusSeconds(7200)).expiresAt(java.time.Instant.now().minusSeconds(3600))
                .claim("sid",TEST_SID.toString()).claim("pwv",com.almahwar.api.security.TokenService.passwordVersion(PASSWORD_CHANGED)).build();
        String expired=encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                org.springframework.security.oauth2.jwt.JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(),claims)).getTokenValue();
        mvc.perform(get(url(path)).header("Authorization","Bearer "+expired)).andExpect(status().isUnauthorized());
        verifyNoInteractions(repository,analytics,reports);
    }
    @ParameterizedTest @MethodSource("paths") void revokedResetAndLiveDowngradeFailBeforeBusinessReads(String path) throws Exception {
        String original=bearer(ADMIN);
        when(sessionRepository.live(any(),anyInt(),anyString(),nullable(String.class),anyBoolean())).thenReturn(false);
        mvc.perform(get(url(path)).header("Authorization",original)).andExpect(status().isUnauthorized());
        when(sessionRepository.live(any(),anyInt(),anyString(),nullable(String.class),anyBoolean())).thenReturn(true);
        var reset=new com.almahwar.api.auth.AuthUserRepository.UserState(1,"admin","A",true,false,PASSWORD_CHANGED.plusSeconds(1),"ADMIN","A",ADMIN.credentialFingerprint());
        when(authUsers.findState(1)).thenReturn(Optional.of(reset));
        mvc.perform(get(url(path)).header("Authorization",original)).andExpect(status().isUnauthorized());
        when(authUsers.findState(1)).thenReturn(Optional.of(state(1,"admin","UNKNOWN","U",true,false)));
        mvc.perform(get(url(path)).header("Authorization",original)).andExpect(status().isForbidden());
        verifyNoInteractions(repository,analytics,reports);
    }
    @ParameterizedTest @MethodSource("paths") void businessWritesAreNotMappedAndResponsesNoStore(String path) throws Exception {
        for(var request:List.of(post(url(path)),put(url(path)),delete(url(path)),patch(url(path))))
            mvc.perform(request.header("Authorization",bearer(ADMIN))).andExpect(status().isMethodNotAllowed());
        mvc.perform(get(url(path)).header("Authorization",bearer(ADMIN))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
    }
    static Stream<String> invalidLists() { return Stream.of("size=101","page=-1","sort=total;DROP TABLE Sales","sort=historicalCost","sort=date,sideways","customerId=0","status=ACTIVE","from=2026-10-07","from=2026-10-08&to=2026-10-07"); }
    @ParameterizedTest @MethodSource("invalidLists") void unsafeInputsNeverReachPersistence(String query) throws Exception {
        for(String path:List.of("invoices","quotations")) mvc.perform(get(url(path)).queryParam(query.split("=",2)[0],query.split("=",2)[1]).header("Authorization",bearer(ADMIN)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.requestId").isNotEmpty());
        verifyNoInteractions(repository);
    }
    @Test void detailBoundsAndNotFoundUseSafeErrors() throws Exception {
        mvc.perform(get(url("invoices/0")).header("Authorization",bearer(ADMIN))).andExpect(status().isBadRequest());
        mvc.perform(get(url("invoices/1")).param("returnsSize","101").header("Authorization",bearer(ADMIN))).andExpect(status().isBadRequest());
        mvc.perform(get(url("quotations/1")).param("q","x").header("Authorization",bearer(ADMIN))).andExpect(status().isBadRequest());
        when(repository.invoice(eq(2),any(),any(),anyBoolean(),anyBoolean())).thenThrow(new ApiException(ErrorCode.NOT_FOUND));
        mvc.perform(get(url("invoices/2")).header("Authorization",bearer(ADMIN))).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
    @Test void connectionFailuresDoNotExposeInternalSql() throws Exception {
        when(repository.invoice(eq(1),any(),any(),anyBoolean(),anyBoolean())).thenThrow(new com.almahwar.dao.DataAccessException("secret SQL",new java.sql.SQLException("private host","08001")));
        String body=mvc.perform(get(url("invoices/1")).header("Authorization",bearer(ADMIN))).andExpect(status().isServiceUnavailable()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("secret SQL","private host","SQLException");
    }
    @Test void readinessIncludesManagerTablesAndKeepsMissingDependencyDetailsPrivate() throws Exception {
        assertThat(com.almahwar.api.health.SchemaCompatibilityChecker.REQUIRED_TABLES).contains("Sales","Sale_Items","Sale_Returns","Sale_Return_Items","Quotations","Quotation_Items","Customers","Suppliers","Expenses","Cash_Transactions","Stock_Movements","Account_Ledger");
        when(databaseStatus.missingTables(anyCollection())).thenReturn(List.of("Quotations"));
        String body=mvc.perform(get("/api/v1/health/ready")).andExpect(status().isServiceUnavailable()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("NOT_READY").doesNotContain("Quotations","db.invalid","test-user");
    }
    @Test void dailyOmitsEveryUnauthorizedSectionAndPreservesRequestedDate() throws Exception {
        mvc.perform(get(url("daily-summary")).param("date","2026-01-01").header("Authorization",bearer(CASHIER)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.date").value("2026-01-01"))
                .andExpect(jsonPath("$.cashbox").doesNotExist()).andExpect(jsonPath("$.expenses").doesNotExist()).andExpect(jsonPath("$.inventorySnapshot").doesNotExist());
        verify(reports).sales(new ManagerDateRange.Input(null,LocalDate.of(2026,1,1),LocalDate.of(2026,1,1)));
        verifyNoMoreInteractions(reports);
    }
}
