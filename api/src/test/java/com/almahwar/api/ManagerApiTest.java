package com.almahwar.api;

import com.almahwar.api.manager.*;
import com.almahwar.api.support.ApiWebTestBase;
import com.almahwar.api.web.*;
import com.almahwar.model.*;
import com.almahwar.model.Reports.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Stream;
import static com.almahwar.api.manager.ManagerResponses.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ManagerApiTest extends ApiWebTestBase {
    @MockitoBean ManagerAnalyticsRepository analytics;
    @MockitoBean ManagerCatalogRepository catalog;
    @MockitoBean ManagerPartyRepository parties;
    @Autowired ManagerQueryService service;
    static final LocalDate TODAY=LocalDate.of(2026,10,7);
    static BigDecimal bd(String s) { return new BigDecimal(s); }
    static final List<String> PATHS=List.of("dashboard","sales/summary","sales/trend","sales/top-products","sales/slow-products",
            "expenses/summary","cashbox/summary","inventory/summary","inventory/low-stock","products/1","products/1/movements",
            "customers","customers/receivables","customers/1","customers/1/account","suppliers","suppliers/payables","suppliers/1","suppliers/1/account");
    static String url(String path) { return "/api/v1/manager/"+path; }
    @BeforeEach void queries() {
        when(analytics.today()).thenReturn(TODAY);
        when(analytics.dashboard()).thenReturn(new DashboardStats(TODAY,bd("10.125"),bd("1.000"),bd("3.000"),bd("0"),1,
                bd("20.000"),bd("2.000"),bd("7.000"),bd("1.000"),bd("99.000"),bd("45.000"),bd("35.000"),2));
        when(analytics.sales(any())).thenReturn(new SalesSummary(bd("10.125"),bd("1.000"),bd("9.125"),1,bd("10.125"),1));
        when(analytics.profit(any())).thenReturn(new ProfitSummary(bd("10.125"),bd("4.125"),bd("6.000"),bd("1"),bd("0.400"),bd("0.600"),bd("5.400"),bd("1"),bd("4.400")));
        when(analytics.movementSummary(any())).thenReturn(new StockMovementSummary(0,bd("0"),bd("0")));
        when(analytics.cashbox(any())).thenReturn(new CashReportSummary(bd("0"),bd("0"),bd("0"),bd("0"),bd("0")));
        when(catalog.inventory(anyBoolean())).thenReturn(new Inventory(2,1,1,null));
        when(catalog.lowStock(any(),anyBoolean())).thenReturn(PageResponse.of(List.of(),new PageQuery(0,20),0));
        Product product=new Product();product.setProductId(1);product.setProductCode("P1");product.setNameAr("منتج");
        product.setSalePrice(bd("2.125"));product.setPurchasePrice(bd("1.125"));product.setQuantity(bd("0.125"));product.setMinimumStock(bd("1.000"));product.setActive(true);
        when(catalog.product(1)).thenReturn(product);
        when(parties.list(any(),any(),anyBoolean(),anyBoolean())).thenReturn(new PartyList(PageResponse.of(List.of(),new PageQuery(0,20),0),null));
        when(parties.detail(any(),eq(1))).thenReturn(new Party(1,"C1","عميل","12345678","Kuwait",true,null));
        when(parties.account(any(),eq(1),any(),any())).thenReturn(new Account(1,new ManagerDateRange(TODAY,TODAY),"0.000","0.000","0.000","0.000",PageResponse.of(List.of(),new PageQuery(0,20),0)));
    }
    static Stream<String> paths() { return PATHS.stream(); }
    @ParameterizedTest @MethodSource("paths") void everyManagerRouteRequiresAuthentication(String path) throws Exception {
        mvc.perform(get(url(path))).andExpect(status().isUnauthorized());
    }
    static Stream<org.junit.jupiter.params.provider.Arguments> roles() {
        return Stream.of("ADMIN","ACCOUNTANT","CASHIER","STOREKEEPER").flatMap(role -> PATHS.stream().map(path ->
                org.junit.jupiter.params.provider.Arguments.of(role,path)));
    }
    @ParameterizedTest @MethodSource("roles") void releasedRoleMatrixIsAppliedAtEveryEndpoint(String role,String path) throws Exception {
        var user=switch(role) { case "ADMIN"->ADMIN;case "ACCOUNTANT"->ACCOUNTANT;case "CASHIER"->CASHIER;default->STOREKEEPER; };
        boolean inventory=path.startsWith("inventory/") || path.equals("sales/slow-products") || path.endsWith("/movements");
        boolean account=path.endsWith("/account") || path.endsWith("receivables") || path.endsWith("payables");
        boolean allowed=role.equals("ADMIN") || path.equals("dashboard") || path.equals("products/1")
                || role.equals("ACCOUNTANT") && !inventory
                || role.equals("CASHIER") && (path.startsWith("sales/") && !path.equals("sales/slow-products") || path.startsWith("customers") && !account)
                || role.equals("STOREKEEPER") && (inventory || path.startsWith("suppliers") && !account);
        mvc.perform(get(url(path)).header("Authorization",bearer(user))).andExpect(status().is(allowed?200:403));
    }
    @ParameterizedTest @MethodSource("paths") void mustChangeCannotEnterAnyManagerRoute(String path) throws Exception {
        mvc.perform(get(url(path)).header("Authorization",bearer(MUST_CHANGE))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
    }
    @ParameterizedTest @MethodSource("paths") void noBusinessPostEndpointExists(String path) throws Exception {
        mvc.perform(post(url(path)).header("Authorization",bearer(ADMIN))).andExpect(status().isMethodNotAllowed());
    }
    @Test void revokedDisabledAndLiveRoleChangesFailBeforeBusinessReads() throws Exception {
        mvc.perform(get(url("dashboard")).header("Authorization",bearer(DISABLED))).andExpect(status().isUnauthorized());
        when(sessionRepository.live(any(),anyInt(),anyString(),nullable(String.class),anyBoolean())).thenReturn(false);
        mvc.perform(get(url("dashboard")).header("Authorization",bearer(ADMIN))).andExpect(status().isUnauthorized());
        verifyNoInteractions(analytics,catalog,parties);
        when(sessionRepository.live(any(),anyInt(),anyString(),nullable(String.class),anyBoolean())).thenReturn(true);
        when(authUsers.findState(1)).thenReturn(Optional.of(state(1,"admin","CASHIER","Cashier",true,false)));
        mvc.perform(get(url("cashbox/summary")).header("Authorization",bearer(ADMIN))).andExpect(status().isForbidden());
    }
    @Test void dashboardRedactsBalancesAndProfitForCashierAndUsesNoStore() throws Exception {
        String body=mvc.perform(get(url("dashboard")).header("Authorization",bearer(CASHIER)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("TODAY_SALES","9.125").doesNotContain("RECEIVABLES","NET_PROFIT","CASH_BALANCE","45.000");
    }
    @Test void financialFieldsAreStringsAndNeverReturnedToCashier() throws Exception {
        mvc.perform(get(url("sales/summary")).header("Authorization",bearer(ADMIN)))
                .andExpect(jsonPath("$.grossSales").value("10.125")).andExpect(jsonPath("$.profit.netProfit").value("4.400"));
        mvc.perform(get(url("sales/summary")).header("Authorization",bearer(CASHIER))).andExpect(jsonPath("$.profit").doesNotExist());
        mvc.perform(get(url("products/1")).header("Authorization",bearer(CASHIER))).andExpect(jsonPath("$.purchaseCost").doesNotExist())
                .andExpect(jsonPath("$.quantity").value("0.125"));
    }
    @Test void businessOutageIs503WithoutExceptionDetails() throws Exception {
        when(analytics.dashboard()).thenThrow(new org.springframework.jdbc.CannotGetJdbcConnectionException("private connection string"));
        String body=mvc.perform(get(url("dashboard")).header("Authorization",bearer(ADMIN))).andExpect(status().isServiceUnavailable())
                .andReturn().getResponse().getContentAsString();assertThat(body).doesNotContain("private","connection");
    }
    @Test void connectionLostInsideCoreQueryIsSafe503AndOtherSqlFailuresRemain500() throws Exception {
        when(analytics.dashboard()).thenThrow(new com.almahwar.dao.DataAccessException("private SQL",
                new java.sql.SQLException("private connection string","08006")));
        String body=mvc.perform(get(url("dashboard")).header("Authorization",bearer(ADMIN)))
                .andExpect(status().isServiceUnavailable()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("private","08006","SQL");
        doThrow(new com.almahwar.dao.DataAccessException("private SQL",
                new java.sql.SQLException("private table","42000"))).when(analytics).dashboard();
        mvc.perform(get(url("dashboard")).header("Authorization",bearer(ADMIN))).andExpect(status().isInternalServerError());
    }
    static Stream<String> invalidFilters() {
        return Stream.of("sales/summary?from=2026-10-08&to=2026-10-07","sales/summary?from=2026-10-01",
                "sales/summary?period=custom","sales/summary?period=this_month&from=2026-10-01&to=2026-10-07",
                "sales/summary?from=2020-01-01&to=2026-10-07","sales/summary?from=bad&to=2026-10-07",
                "sales/trend?grouping=DROP","sales/trend?from=2020-01-01&to=2026-10-07&grouping=monthly",
                "sales/top-products?limit=0","sales/top-products?limit=101","sales/slow-products?days=0",
                "inventory/low-stock?size=101","inventory/low-stock?page=-1","inventory/low-stock?sort=cost",
                "customers?sort=unknown","suppliers?page=10001","products/0");
    }
    @ParameterizedTest @MethodSource("invalidFilters") void invalidFiltersUseSafe400(String path) throws Exception {
        mvc.perform(get(url(path)).header("Authorization",bearer(ADMIN))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
    @Test void customTodayWeekMonthAndChronologicalZeroFilledTrendAreSupported() throws Exception {
        for(String preset:List.of("today","this_week","this_month"))
            mvc.perform(get(url("sales/summary")).param("period",preset).header("Authorization",bearer(ADMIN))).andExpect(status().isOk());
        mvc.perform(get(url("sales/trend")).param("from","2026-10-01").param("to","2026-10-03").header("Authorization",bearer(ADMIN)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.buckets.length()").value(3))
                .andExpect(jsonPath("$.buckets[0].bucket").value("2026-10-01")).andExpect(jsonPath("$.buckets[2].bucket").value("2026-10-03"));
    }
    @Test void longSearchAndSortInjectionAreRejectedWithoutRepositoryReads() throws Exception {
        // Validation of these lists belongs to their bounded read repository; SQL integration verifies literal escaping.
        assertThatThrownBy(()->new ManagerPage(0,20,"x".repeat(101),null).search()).isInstanceOf(com.almahwar.api.error.FieldValidationException.class);
        assertThatThrownBy(()->new ManagerPage(0,20,null,"name; DROP TABLE Sales").order(Map.of("name","p.name"),"name"))
                .isInstanceOf(com.almahwar.api.error.FieldValidationException.class);
    }
}
