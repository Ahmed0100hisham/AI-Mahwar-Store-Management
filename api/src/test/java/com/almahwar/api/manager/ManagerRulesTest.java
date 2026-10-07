package com.almahwar.api.manager;

import com.almahwar.api.core.SpringSecurityContext;
import com.almahwar.api.security.*;
import com.almahwar.model.PartyType;
import com.almahwar.service.RolePermissions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.context.SecurityContextHolder;
import java.time.LocalDate;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManagerRulesTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    @Test void presetsMatchReleasedSundayWeekAndWholeMonth() {
        var today=LocalDate.of(2026,10,7);
        assertThat(ManagerDateRange.resolve(new ManagerDateRange.Input("this_week",null,null),366,()->today).from()).isEqualTo(LocalDate.of(2026,10,4));
        assertThat(ManagerDateRange.resolve(new ManagerDateRange.Input("this_month",null,null),366,()->today).to()).isEqualTo(LocalDate.of(2026,10,31));
    }
    @ParameterizedTest @ValueSource(strings={"ADMIN","ACCOUNTANT","CASHIER","STOREKEEPER"})
    void corePermissionMatrixGovernsAccessWithoutHttp(String role) {
        SecurityContextHolder.getContext().setAuthentication(new ApiAuthenticationToken(new ApiUser(1,"u","U",role,role,false,RolePermissions.forRole(role),null)));
        var security=new SpringSecurityContext();var access=new ManagerAccess(security);
        assertThat(access.allowed("dashboard")).isTrue();
        assertThat(access.allowed("cashbox")).isEqualTo(Set.of("ADMIN","ACCOUNTANT").contains(role));
        assertThat(access.allowed("inventory")).isEqualTo(Set.of("ADMIN","STOREKEEPER").contains(role));
        assertThat(access.allowed("customerAccounts")).isEqualTo(Set.of("ADMIN","ACCOUNTANT").contains(role));
        assertThat(access.allowed("supplierAccounts")).isEqualTo(Set.of("ADMIN","ACCOUNTANT").contains(role));
        var analytics=mock(ManagerAnalyticsRepository.class);var catalog=mock(ManagerCatalogRepository.class);var parties=mock(ManagerPartyRepository.class);
        var service=new ManagerQueryService(access,security,analytics,catalog,parties);
        if(!access.allowed("inventory")) assertThatThrownBy(service::inventory).isInstanceOf(com.almahwar.service.AccessDeniedException.class);
        if(!access.allowed("cashbox")) assertThatThrownBy(()->service.cashbox(null)).isInstanceOf(com.almahwar.service.AccessDeniedException.class);
        if(!access.allowed("supplierAccounts")) assertThatThrownBy(()->service.account(PartyType.SUPPLIER,1,null,null)).isInstanceOf(com.almahwar.service.AccessDeniedException.class);
        verifyNoInteractions(analytics,catalog,parties);
    }
    @Test void forgedPrincipalCannotGrantAnExtraPermission() {
        SecurityContextHolder.getContext().setAuthentication(new ApiAuthenticationToken(new ApiUser(1,"u","U","CASHIER","Cashier",false,
                RolePermissions.forRole("ADMIN"),null)));
        assertThat(new ManagerAccess(new SpringSecurityContext()).allowed("cashbox")).isFalse();
    }
    @Test void dateBoundsRejectOverflowAndDoNotFetchClockForCustomRange() {
        assertThatThrownBy(()->ManagerDateRange.resolve(new ManagerDateRange.Input(null,LocalDate.of(2026,1,1),LocalDate.MAX),366,()->null))
                .isInstanceOf(com.almahwar.api.error.FieldValidationException.class);
        var day=LocalDate.of(2026,10,7);
        assertThat(ManagerDateRange.resolve(new ManagerDateRange.Input(null,day,day),366,()->{throw new AssertionError("clock read");})).isEqualTo(new ManagerDateRange(day,day));
    }
}
