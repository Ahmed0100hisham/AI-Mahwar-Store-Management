package com.almahwar.api.manager;

import com.almahwar.api.core.SpringSecurityContext;
import com.almahwar.api.security.*;
import com.almahwar.service.RolePermissions;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.context.SecurityContextHolder;
import java.nio.file.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManagerDocumentRulesTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    private static void principal(String role,boolean restricted,Set<com.almahwar.model.Permission> permissions) {
        SecurityContextHolder.getContext().setAuthentication(new ApiAuthenticationToken(new ApiUser(1,"u","U",role,role,restricted,permissions,null)));
    }
    @Test void forgedAuthoritiesAndRestrictedPrincipalsCannotBypassService() {
        var security=new SpringSecurityContext();var access=new ManagerAccess(security);
        var repo=mock(ManagerDocumentRepository.class);var analytics=mock(ManagerAnalyticsRepository.class);var reports=mock(ManagerQueryService.class);
        var service=new ManagerDocumentService(access,security,repo,analytics,reports);
        for(String role:new String[]{"STOREKEEPER","UNKNOWN"}) {
            principal(role,false,RolePermissions.forRole("ADMIN"));
            assertThatThrownBy(()->service.invoices(null,null,null)).isInstanceOf(com.almahwar.service.AccessDeniedException.class);
            assertThatThrownBy(()->service.invoice(1,null,null,null)).isInstanceOf(com.almahwar.service.AccessDeniedException.class);
            assertThatThrownBy(()->service.quotations(null,null,null)).isInstanceOf(com.almahwar.service.AccessDeniedException.class);
            assertThatThrownBy(()->service.quotation(1,null)).isInstanceOf(com.almahwar.service.AccessDeniedException.class);
        }
        principal("ADMIN",true,RolePermissions.forRole("ADMIN"));
        assertThatThrownBy(()->service.daily(null)).isInstanceOf(com.almahwar.service.AccessDeniedException.class);
        assertThatThrownBy(()->service.invoice(1,null,null,null)).isInstanceOf(com.almahwar.service.AccessDeniedException.class);
        verifyNoInteractions(repo,analytics,reports);
    }
    @Test void quotationPermissionAloneDoesNotExposeSaleLink() {
        principal("ADMIN",false,Set.of(com.almahwar.model.Permission.QUOTATIONS_VIEW));
        var security=new SpringSecurityContext();var repo=mock(ManagerDocumentRepository.class);var analytics=mock(ManagerAnalyticsRepository.class);
        when(analytics.today()).thenReturn(LocalDate.of(2026,10,7));
        var service=new ManagerDocumentService(new ManagerAccess(security),security,repo,analytics,mock(ManagerQueryService.class));
        service.quotation(1,new ManagerPage(null,null,null,null));
        verify(repo).quotation(eq(1),any(),any(),eq(false));
    }
    @ParameterizedTest @ValueSource(strings={"0","-0.001","999999999999999.999","1.2345","0.0005"})
    void exactDecimalStringsUseCoreRounding(String value) {
        var decimal=new BigDecimal(value);
        assertThat(ManagerResponses.money(decimal)).isEqualTo(com.almahwar.util.MoneyUtil.of(decimal).toPlainString()).matches("-?\\d+\\.\\d{3}");
        assertThat(ManagerResponses.quantity(decimal)).isEqualTo(com.almahwar.util.QuantityUtil.of(decimal).toPlainString()).matches("-?\\d+\\.\\d{3}");
    }
    @Test void everyNewBusinessMappingIsGetOnlyAndGuarded() {
        int count=0;
        for(var method:ManagerDocumentController.class.getDeclaredMethods()) {
            if(!java.lang.reflect.Modifier.isPublic(method.getModifiers())) continue;
            assertThat(method.isAnnotationPresent(org.springframework.web.bind.annotation.GetMapping.class)).isTrue();
            assertThat(method.isAnnotationPresent(org.springframework.security.access.prepost.PreAuthorize.class)).isTrue();count++;
        }
        assertThat(count).isEqualTo(5);
    }
    @Test void documentReadsCannotCallMutationServicesOrUnsafeSql() throws Exception {
        Path root=Path.of("src/main/java/com/almahwar/api/manager");
        for(String name:new String[]{"ManagerDocumentRepository","ManagerDocumentService","ManagerDocumentController"}) {
            String text=Files.readString(root.resolve(name+".java"));
            assertThat(text).doesNotContain("QuotationServiceImpl","SaleServiceImpl","nextNumber(","expireOverdue(","NOLOCK","SELECT *","double ","float ");
            assertThat(java.util.regex.Pattern.compile("(?i)\\b(INSERT|UPDATE|DELETE|MERGE|ALTER|CREATE|DROP)\\s+(INTO\\s+)?dbo\\.").matcher(text).find()).isFalse();
        }
        String sql=Files.readString(root.resolve("ManagerDocumentRepository.java"));
        assertThat(sql).doesNotContain("purchase_price","queryList(\"SELECT *").contains("OFFSET ? ROWS FETCH NEXT ? ROWS ONLY","likeContains(input.search())");
        assertThat(Files.readString(root.resolve("ManagerDocumentService.java"))).contains("reports.sales(input)","reports.expenses(input)","reports.cashbox(input)");
    }
}
