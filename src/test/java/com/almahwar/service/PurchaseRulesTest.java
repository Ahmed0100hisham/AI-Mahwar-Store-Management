package com.almahwar.service;

import com.almahwar.dao.AccountLedgerDao;
import com.almahwar.dao.AuditLogDao;
import com.almahwar.dao.CashTransactionDao;
import com.almahwar.dao.CustomerDao;
import com.almahwar.dao.ProductDao;
import com.almahwar.dao.PurchaseDao;
import com.almahwar.dao.StockMovementDao;
import com.almahwar.dao.SupplierDao;
import com.almahwar.dao.UnitDao;
import com.almahwar.model.PaymentMethod;
import com.almahwar.model.PaymentStatus;
import com.almahwar.model.PaymentType;
import com.almahwar.model.Permission;
import com.almahwar.model.Purchase;
import com.almahwar.model.PurchaseItem;
import com.almahwar.model.Role;
import com.almahwar.service.PaymentRules.PaymentPlan;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Purchase calculations, payment rules, costing and permissions — no database needed. */
class PurchaseRulesTest {

    private final TestSecurity security = new TestSecurity();
    private final PurchaseService purchases = new PurchaseServiceImpl(new PurchaseDao(), new SupplierDao(), new ProductDao(),
            new UnitDao(), new StockLedger(new StockMovementDao()),
            new AccountLedger(new AccountLedgerDao(), new CustomerDao(), new SupplierDao()),
            new CashTransactionDao(), new AuditLogDao(), CostingPolicy.LAST_PURCHASE_COST, security);

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static PurchaseItem item(String qty, String cost, String discount) {
        PurchaseItem i = new PurchaseItem();
        i.setProductId(1);
        i.setProductName("صنف");
        i.setQuantity(d(qty));
        i.setUnitCost(d(cost));
        i.setDiscountAmount(d(discount));
        return i;
    }

    private static Purchase purchase(String discount, PurchaseItem... items) {
        Purchase p = new Purchase();
        p.setSupplierId(1);
        p.setItems(List.of(items));
        p.setDiscountAmount(d(discount));
        return p;
    }

    // ---------- Calculations ----------

    @Test
    void lineTotalsAndInvoiceTotals() {
        Purchase p = purchase("5.250",
                item("12", "1.375", "0"),        // 16.500
                item("2.5", "3.333", "0.250"),   // 8.3325 - 0.25 = 8.0825 → 8.083 (HALF_UP like SQL Server)
                item("1", "45", "5"))            // 40.000
                .recalculate();
        assertEquals(d("16.500"), p.getItems().get(0).getLineTotal());
        assertEquals(d("8.083"), p.getItems().get(1).getLineTotal());
        assertEquals(d("40.000"), p.getItems().get(2).getLineTotal());
        assertEquals(d("64.583"), p.getSubtotal());
        assertEquals(d("59.333"), p.getTotalAmount(), "total = subtotal - discount");
        p.setPaidAmount(d("20"));
        assertEquals(d("39.333"), p.getRemainingAmount());
        assertEquals(PaymentStatus.PARTIAL, p.getPaymentStatus());
    }

    @Test
    void netUnitCostIncludesTheLineDiscount() {
        assertEquals(d("40.000"), item("1", "45", "5").getNetUnitCost());
        assertEquals(d("1.250"), item("4", "1.500", "1").getNetUnitCost());
    }

    // ---------- Payment rules ----------

    @Test
    void immediateMethodsPayTheWholeTotal() {
        for (PaymentType t : new PaymentType[]{PaymentType.CASH, PaymentType.KNET, PaymentType.BANK_TRANSFER,
                PaymentType.CHEQUE}) {
            PaymentPlan plan = PaymentRules.resolve(t, null, null, d("120.500"));
            assertEquals(d("120.500"), plan.paid(), t.name());
            assertEquals(t.getMethod(), plan.method());
            assertEquals(d("0.000"), plan.remaining(d("120.500")));
        }
    }

    @Test
    void creditPaysNothing() {
        PaymentPlan plan = PaymentRules.resolve(PaymentType.CREDIT, PaymentMethod.CASH, d("50"), d("120"));
        assertEquals(d("0.000"), plan.paid());
        assertEquals(PaymentMethod.CREDIT, plan.method());
        assertFalse(plan.movesMoney());
    }

    @Test
    void partialMustBeBetweenZeroAndTotal() {
        PaymentPlan ok = PaymentRules.resolve(PaymentType.PARTIAL, PaymentMethod.KNET, d("40"), d("120"));
        assertEquals(d("40.000"), ok.paid());
        assertEquals(PaymentMethod.KNET, ok.method());

        assertEquals("المبلغ المدفوع لا يمكن أن يتجاوز إجمالي الفاتورة.", paidError(d("120.001")));
        assertTrue(paidError(d("0")).contains("آجل"));
        assertTrue(paidError(d("120")).contains("كاملة"));
        assertEquals("المبلغ المدفوع لا يمكن أن يكون سالبًا.", paidError(d("-1")));
        assertTrue(paidError(d("1.0005")).contains("3 منازل"));
        ValidationException noMethod = assertThrows(ValidationException.class,
                () -> PaymentRules.resolve(PaymentType.PARTIAL, PaymentMethod.CREDIT, d("10"), d("120")));
        assertNotNull(noMethod.errorFor(PaymentRules.METHOD));
        assertThrows(ValidationException.class, () -> PaymentRules.resolve(null, null, null, d("1")));
    }

    private static String paidError(BigDecimal paid) {
        return assertThrows(ValidationException.class,
                () -> PaymentRules.resolve(PaymentType.PARTIAL, PaymentMethod.CASH, paid, d("120")))
                .errorFor(PaymentRules.PAID);
    }

    @Test
    void storedDocumentsMapBackToTheirPaymentType() {
        assertEquals(PaymentType.CREDIT, PaymentType.of(PaymentMethod.CREDIT, d("10"), d("0")));
        assertEquals(PaymentType.PARTIAL, PaymentType.of(PaymentMethod.KNET, d("10"), d("4")));
        assertEquals(PaymentType.CHEQUE, PaymentType.of(PaymentMethod.CHEQUE, d("10"), d("10")));
        assertEquals(PaymentStatus.UNPAID, PaymentStatus.of(d("10"), d("0")));
        assertEquals(PaymentStatus.PAID, PaymentStatus.of(d("10"), d("10")));
    }

    // ---------- Validation ----------

    @Test
    void invalidPurchasesAreRejectedInArabic() {
        Purchase empty = new Purchase();
        ValidationException e = assertThrows(ValidationException.class,
                () -> PurchaseServiceImpl.validateFields(empty).throwIfAny());
        assertEquals("اختر المورد.", e.errorFor(PurchaseService.SUPPLIER));
        assertEquals("أضف صنفًا واحدًا على الأقل إلى الفاتورة.", e.errorFor(PurchaseService.ITEMS));

        assertTrue(itemsError(item("0", "1", "0")).contains("الكمية يجب أن تكون أكبر من صفر"));
        assertTrue(itemsError(item("-2", "1", "0")).contains("الكمية"));
        assertTrue(itemsError(item("1", "-0.001", "0")).contains("سعر الشراء لا يمكن أن يكون سالبًا"));
        assertTrue(itemsError(item("1", "5", "-1")).contains("خصم السطر"));
        assertTrue(itemsError(item("2", "5", "10.001")).contains("أكبر من قيمته"));
        assertTrue(itemsError(item("1.0001", "5", "0")).contains("3 منازل"));

        ValidationException negativeTotal = assertThrows(ValidationException.class,
                () -> PurchaseServiceImpl.validateFields(purchase("10.001", item("1", "10", "0"))).throwIfAny());
        assertTrue(negativeTotal.errorFor(PurchaseService.DISCOUNT).contains("سالبًا"));
    }

    private static String itemsError(PurchaseItem i) {
        return assertThrows(ValidationException.class,
                () -> PurchaseServiceImpl.validateFields(purchase("0", i)).throwIfAny()).errorFor(PurchaseService.ITEMS);
    }

    @Test
    void zeroCostLinesAreAllowed() {
        PurchaseServiceImpl.validateFields(purchase("0", item("3", "0", "0"))).throwIfAny();   // free goods
    }

    // ---------- Costing ----------

    @Test
    void lastPurchaseCostPolicy() {
        assertEquals(d("2.750"), CostingPolicy.LAST_PURCHASE_COST.costAfterReceipt(d("2.000"), d("100"), d("10"), d("2.750")));
    }

    @Test
    void weightedAverageCanReplaceItLater() {
        // 100 @ 2.000 and 50 @ 2.600: (200 + 130) / 150 = 2.200
        assertEquals(d("2.200"), CostingPolicy.WEIGHTED_AVERAGE.costAfterReceipt(d("2.000"), d("100"), d("50"), d("2.600")));
        assertEquals(d("3.000"), CostingPolicy.WEIGHTED_AVERAGE.costAfterReceipt(d("9.999"), d("0"), d("5"), d("3.000")));
    }

    // ---------- Permissions ----------

    @Test
    void roleMatrix() {
        Set<Permission> storekeeper = RolePermissions.forRole(Role.STOREKEEPER);
        assertTrue(storekeeper.containsAll(Set.of(Permission.PURCHASES_VIEW, Permission.PURCHASES_CREATE,
                Permission.PURCHASES_POST, Permission.PURCHASE_COST_VIEW)));
        Set<Permission> accountant = RolePermissions.forRole(Role.ACCOUNTANT);
        assertTrue(accountant.containsAll(Set.of(Permission.PURCHASES_VIEW, Permission.PURCHASE_COST_VIEW)));
        assertFalse(accountant.contains(Permission.PURCHASES_CREATE));
        assertFalse(accountant.contains(Permission.PURCHASES_POST));
        Set<Permission> cashier = RolePermissions.forRole(Role.CASHIER);
        assertFalse(cashier.contains(Permission.PURCHASES_VIEW));
    }

    @Test
    void permissionsAreCheckedByTheService() {
        Purchase p = purchase("0", item("1", "1", "0"));
        security.as(Role.CASHIER, 1);
        assertThrows(AccessDeniedException.class, () -> purchases.search(null));
        assertThrows(AccessDeniedException.class, () -> purchases.saveDraft(p, PaymentType.CASH));

        security.as(Role.ACCOUNTANT, 1);
        assertThrows(AccessDeniedException.class, () -> purchases.saveDraft(p, PaymentType.CASH));
        assertThrows(AccessDeniedException.class, () -> purchases.saveAndPost(p, PaymentType.CASH));
        assertThrows(AccessDeniedException.class, () -> purchases.post(1));
        assertThrows(AccessDeniedException.class, () -> purchases.cancelDraft(1));

        security.logout();
        assertThrows(AccessDeniedException.class, () -> purchases.findById(1));
        assertNull(security.getSession().orElse(null));
    }
}
