package com.almahwar.service;

import com.almahwar.dao.AccountLedgerDao;
import com.almahwar.dao.AuditLogDao;
import com.almahwar.dao.CustomerDao;
import com.almahwar.dao.SupplierDao;
import com.almahwar.model.CreditStatus;
import com.almahwar.model.Customer;
import com.almahwar.model.LedgerEntryType;
import com.almahwar.model.PartyFilter;
import com.almahwar.model.PartyType;
import com.almahwar.model.Permission;
import com.almahwar.model.Role;
import com.almahwar.model.Supplier;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Customer / supplier / ledger rules decided before the database is touched. */
class CustomerSupplierRulesTest {

    private final TestSecurity security = new TestSecurity();
    private final AccountLedger ledger = new AccountLedger(new AccountLedgerDao(), new CustomerDao(), new SupplierDao());
    private final CustomerService customers = new CustomerServiceImpl(new CustomerDao(), ledger, new AuditLogDao(), security);
    private final SupplierService suppliers = new SupplierServiceImpl(new SupplierDao(), ledger, new AuditLogDao(), security);

    private static Customer customer(String limit, String balance) {
        Customer c = new Customer();
        c.setCustomerCode("C-1");
        c.setName("مؤسسة الخليج");
        c.setCreditLimit(new BigDecimal(limit));
        c.setBalance(new BigDecimal(balance));
        return c;
    }

    // ---------- Credit limit ----------

    @Test
    void availableCreditIsLimitMinusDebt() {
        CreditStatus s = CreditStatus.of(new BigDecimal("500"), new BigDecimal("450.250"));
        assertEquals(new BigDecimal("49.750"), s.availableCredit());
        assertFalse(s.overLimit());
        assertTrue(s.allows(new BigDecimal("49.750")));
        assertFalse(s.allows(new BigDecimal("49.751")));
        assertTrue(s.allows(BigDecimal.ZERO), "paying in full never needs credit");
    }

    @Test
    void overLimitHasNoAvailableCredit() {
        CreditStatus s = CreditStatus.of(new BigDecimal("300"), new BigDecimal("320"));
        assertTrue(s.overLimit());
        assertEquals(new BigDecimal("0.000"), s.availableCredit());
    }

    @Test
    void customerInCreditGetsLimitPlusCredit() {
        CreditStatus s = CreditStatus.of(new BigDecimal("100"), new BigDecimal("-25"));
        assertEquals(new BigDecimal("125.000"), s.availableCredit());
    }

    @Test
    void creditDecisionsForPos() {
        assertTrue(CustomerServiceImpl.decide(customer("500", "100"), new BigDecimal("400")).allowed());
        CustomerService.CreditDecision refused = CustomerServiceImpl.decide(customer("500", "100"), new BigDecimal("400.001"));
        assertFalse(refused.allowed());
        assertTrue(refused.message().contains("يتجاوز"));
        CustomerService.CreditDecision cashOnly = CustomerServiceImpl.decide(customer("0", "0"), BigDecimal.ONE);
        assertFalse(cashOnly.allowed());
        assertTrue(cashOnly.message().contains("نقدي"));

        Customer inactive = customer("500", "0");
        inactive.setActive(false);
        assertFalse(CustomerServiceImpl.decide(inactive, BigDecimal.ONE).allowed());

        Customer walkIn = customer("0", "0");
        walkIn.setCustomerCode(Customer.CASH_CUSTOMER_CODE);
        assertFalse(CustomerServiceImpl.decide(walkIn, BigDecimal.ONE).allowed());
        assertTrue(CustomerServiceImpl.decide(walkIn, BigDecimal.ZERO).allowed());
    }

    // ---------- Validation ----------

    @Test
    void customerNameIsRequiredAndPhoneMustBeValid() {
        Customer c = new Customer();
        c.setPhone("12345");
        c.setEmail("not-an-email");
        c.setCreditLimit(new BigDecimal("-1"));
        ValidationException e = assertThrows(ValidationException.class, () -> CustomerServiceImpl.validate(c).throwIfAny());
        assertEquals("اسم العميل مطلوب.", e.errorFor(CustomerService.NAME));
        assertTrue(e.errorFor(CustomerService.PHONE).contains("8 أرقام"));
        assertEquals("البريد الإلكتروني غير صحيح.", e.errorFor(CustomerService.EMAIL));
        assertEquals("حد الائتمان يجب أن يكون صفرًا أو أكثر.", e.errorFor(CustomerService.CREDIT_LIMIT));
    }

    @Test
    void supplierNameIsRequired() {
        Supplier s = new Supplier();
        s.setPhone("+97150");
        ValidationException e = assertThrows(ValidationException.class, () -> SupplierServiceImpl.validate(s).throwIfAny());
        assertEquals("اسم المورد مطلوب.", e.errorFor(SupplierService.NAME));
        assertNotNull(e.errorFor(SupplierService.PHONE));
    }

    @Test
    void openingBalanceWithTooManyDecimalsIsRejected() {
        security.as(Role.ACCOUNTANT, 1);
        Customer c = customer("0", "0");
        ValidationException e = assertThrows(ValidationException.class,
                () -> customers.create(c, new BigDecimal("10.1234")));
        assertTrue(e.errorFor(CustomerService.OPENING_BALANCE).contains("3 منازل"));
    }

    // ---------- Ledger rules ----------

    @Test
    void entryTypesBelongToTheirParty() {
        assertTrue(LedgerEntryType.SALE.allowedFor(PartyType.CUSTOMER));
        assertFalse(LedgerEntryType.SALE.allowedFor(PartyType.SUPPLIER));
        assertTrue(LedgerEntryType.PURCHASE_RETURN.allowedFor(PartyType.SUPPLIER));
        assertFalse(LedgerEntryType.PURCHASE.allowedFor(PartyType.CUSTOMER));
        for (LedgerEntryType both : new LedgerEntryType[]{LedgerEntryType.OPENING_BALANCE, LedgerEntryType.PAYMENT,
                LedgerEntryType.ADJUSTMENT}) {
            assertTrue(both.allowedFor(PartyType.CUSTOMER) && both.allowedFor(PartyType.SUPPLIER), both.name());
        }
        assertThrows(IllegalArgumentException.class, () -> ledger.post(null, PartyType.SUPPLIER, 1,
                LedgerEntryType.SALE, BigDecimal.ONE, null, null, null, null, 1));
        assertThrows(IllegalArgumentException.class, () -> ledger.post(null, PartyType.CUSTOMER, 1,
                LedgerEntryType.PAYMENT, BigDecimal.ZERO, null, null, null, null, 1));
    }

    @Test
    void balanceSidesPerParty() {
        BigDecimal debit = new BigDecimal("100"), credit = new BigDecimal("30");
        assertEquals(new BigDecimal("70"), PartyType.CUSTOMER.balanceEffect(debit, credit));
        assertEquals(new BigDecimal("-70"), PartyType.SUPPLIER.balanceEffect(debit, credit));
    }

    // ---------- Permissions ----------

    @Test
    void roleMatrix() {
        Set<Permission> cashier = RolePermissions.forRole(Role.CASHIER);
        assertTrue(cashier.containsAll(Set.of(Permission.CUSTOMERS_VIEW, Permission.CUSTOMERS_EDIT)));
        assertFalse(cashier.contains(Permission.CUSTOMER_BALANCE_VIEW));
        assertFalse(cashier.contains(Permission.SUPPLIERS_VIEW));

        Set<Permission> storekeeper = RolePermissions.forRole(Role.STOREKEEPER);
        assertTrue(storekeeper.contains(Permission.SUPPLIERS_VIEW));
        assertFalse(storekeeper.contains(Permission.SUPPLIERS_EDIT));
        assertFalse(storekeeper.contains(Permission.SUPPLIER_BALANCE_VIEW));
        assertFalse(storekeeper.contains(Permission.CUSTOMERS_VIEW));

        Set<Permission> accountant = RolePermissions.forRole(Role.ACCOUNTANT);
        assertTrue(accountant.containsAll(Set.of(Permission.CUSTOMERS_VIEW, Permission.CUSTOMERS_EDIT,
                Permission.CUSTOMER_BALANCE_VIEW, Permission.SUPPLIERS_VIEW, Permission.SUPPLIERS_EDIT,
                Permission.SUPPLIER_BALANCE_VIEW)));
    }

    @Test
    void cashierCannotTouchBalancesOrSuppliers() {
        security.as(Role.CASHIER, 1);
        AccessDeniedException opening = assertThrows(AccessDeniedException.class,
                () -> customers.create(customer("0", "0"), new BigDecimal("50")));
        assertTrue(opening.getMessage().contains("رصيد افتتاحي"));
        assertThrows(AccessDeniedException.class, () -> customers.statement(1, null, null, null));
        assertThrows(AccessDeniedException.class, () -> customers.creditStatus(1));
        assertThrows(AccessDeniedException.class, () -> suppliers.search(PartyFilter.all()));
    }

    @Test
    void storekeeperOnlyViewsSuppliers() {
        security.as(Role.STOREKEEPER, 1);
        Supplier s = new Supplier();
        s.setName("مورد");
        assertThrows(AccessDeniedException.class, () -> suppliers.create(s, null));
        assertThrows(AccessDeniedException.class, () -> suppliers.setActive(1, false));
        assertThrows(AccessDeniedException.class, () -> suppliers.statement(1, null, null, null));
        assertThrows(AccessDeniedException.class, () -> customers.search(PartyFilter.all()));
    }

    @Test
    void nothingWithoutLogin() {
        security.logout();
        assertThrows(AccessDeniedException.class, () -> customers.search(null));
        assertThrows(AccessDeniedException.class, () -> suppliers.search(null));
        assertNull(security.getSession().orElse(null));
    }
}
