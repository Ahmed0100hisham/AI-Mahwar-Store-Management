package com.almahwar.dao;

import com.almahwar.model.Category;
import com.almahwar.model.Customer;
import com.almahwar.model.DashboardLists.SalesPoint;
import com.almahwar.model.DashboardStats;
import com.almahwar.model.Product;
import com.almahwar.model.Role;
import com.almahwar.model.Supplier;
import com.almahwar.model.Unit;
import com.almahwar.model.User;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks every dashboard figure against a small, fully known set of rows:
 * the stats are read before and after inserting them and the differences
 * must match hand-calculated values. Enable with {@code -Ddb.it=true}.
 */
@EnabledIfSystemProperty(named = "db.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DashboardDaoIntegrationTest {

    private final String tag = "IT-" + UUID.randomUUID().toString().substring(0, 8);
    private final DashboardDao dao = new DashboardDao();
    private final BaseDao sql = new BaseDao() { };

    private Integer userId, productId, customerId, supplierId, saleId;

    private static BigDecimal kwd(String v) {
        return new BigDecimal(v);
    }

    @AfterAll
    void cleanUp() {
        if (saleId != null) {
            sql.update("DELETE FROM dbo.Sale_Returns WHERE sale_id = ?", saleId);   // items cascade
            sql.update("DELETE FROM dbo.Sales WHERE sale_id = ?", saleId);          // items cascade
        }
        sql.update("DELETE FROM dbo.Cash_Transactions WHERE description = ?", tag);
        sql.update("DELETE FROM dbo.Expenses WHERE description = ?", tag);
        if (productId != null) new ProductDao().delete(productId);
        if (customerId != null) new CustomerDao().delete(customerId);
        if (supplierId != null) new SupplierDao().delete(supplierId);
        if (userId != null) sql.update("DELETE FROM dbo.Users WHERE user_id = ?", userId);
    }

    @Test
    void figuresMatchHandCalculatedValues() {
        DashboardStats before = dao.loadStats();

        // --- known data -------------------------------------------------------
        User user = new User();
        user.setUsername("dash_" + tag.substring(3));
        user.setPasswordHash("x");
        user.setFullName("اختبار لوحة التحكم");
        user.setRoleId(new RoleDao().findByCode(Role.CASHIER).orElseThrow().getRoleId());
        userId = new UserDao().insert(user);

        Category category = new CategoryDao().findAllActive().get(0);
        Unit piece = new UnitDao().findAll().stream().filter(u -> !u.isAllowsDecimal()).findFirst().orElseThrow();
        Product p = new Product();
        p.setProductCode(tag);
        p.setNameAr("منتج اختبار " + tag);
        p.setCategoryId(category.getCategoryId());
        p.setUnitId(piece.getUnitId());
        p.setPurchasePrice(kwd("6.000"));
        p.setSalePrice(kwd("10.000"));
        p.setQuantity(kwd("2"));
        p.setMinimumStock(kwd("5"));            // => +1 low-stock product
        productId = new ProductDao().insert(p);

        Customer c = new Customer();
        c.setCustomerCode(tag);
        c.setName("عميل " + tag);
        customerId = new CustomerDao().insert(c);

        Supplier s = new Supplier();
        s.setSupplierCode(tag);
        s.setName("مورد " + tag);
        s.setOpeningBalance(kwd("5.000"));      // => +5.000 payables
        supplierId = new SupplierDao().insert(s);

        // Sale now: 2 × 10.000 − line discount 1.000 = 19.000; invoice discount 0.500 => total 18.500
        // margin = 19.000 − 2 × 6.000 = 7.000; paid 10.000 => 8.500 owed by the customer
        saleId = sql.insert("""
                INSERT INTO dbo.Sales (invoice_no, sale_date, customer_id, user_id, payment_method,
                                       subtotal, discount_amount, total_amount, paid_amount)
                VALUES (?, SYSDATETIME(), ?, ?, 'CREDIT', 19.000, 0.500, 18.500, 10.000)
                """, tag, customerId, userId);
        int saleItemId = sql.insert("""
                INSERT INTO dbo.Sale_Items (sale_id, product_id, quantity, unit_price, purchase_price, discount_amount)
                VALUES (?, ?, 2, 10.000, 6.000, 1.000)
                """, saleId, productId);
        sql.update("UPDATE dbo.Customers SET balance = balance + 8.500 WHERE customer_id = ?", customerId);
        sql.update("""
                INSERT INTO dbo.Cash_Transactions (transaction_type, amount, payment_method, source_type, source_id,
                                                   description, user_id)
                VALUES ('IN', 10.000, 'CASH', 'SALE', ?, ?, ?)
                """, saleId, tag, userId);

        // Return 1 unit today: revenue 10.000 back, cost 6.000 back into stock => profit −4.000
        int returnId = sql.insert("""
                INSERT INTO dbo.Sale_Returns (return_no, sale_id, customer_id, total_amount, refund_amount, user_id)
                VALUES (?, ?, ?, 10.000, 0, ?)
                """, tag, saleId, customerId, userId);
        sql.update("""
                INSERT INTO dbo.Sale_Return_Items (return_id, sale_item_id, product_id, quantity, unit_price)
                VALUES (?, ?, ?, 1, 10.000)
                """, returnId, saleItemId, productId);

        // Expense today 2.000, paid from the cash box
        sql.update("""
                INSERT INTO dbo.Expenses (expense_no, expense_type, amount, description, user_id)
                VALUES (?, N'اختبار', 2.000, ?, ?)
                """, tag, tag, userId);
        sql.update("""
                INSERT INTO dbo.Cash_Transactions (transaction_type, amount, payment_method, source_type,
                                                   description, user_id)
                VALUES ('OUT', 2.000, 'CASH', 'EXPENSE', ?, ?)
                """, tag, userId);

        // --- expected differences ----------------------------------------------
        DashboardStats after = dao.loadStats();
        assertEquals(kwd("18.500"), after.todaySales().subtract(before.todaySales()), "today sales");
        assertEquals(1, after.todayInvoices() - before.todayInvoices(), "today invoices");
        assertEquals(kwd("18.500"), after.monthSales().subtract(before.monthSales()), "month sales");
        assertEquals(kwd("2.500"), after.monthGrossProfit().subtract(before.monthGrossProfit()),
                "gross profit = 7.000 margin − 0.500 invoice discount − 4.000 return");
        assertEquals(kwd("2.000"), after.monthExpenses().subtract(before.monthExpenses()), "expenses");
        assertEquals(kwd("0.500"), after.monthNetProfit().subtract(before.monthNetProfit()), "net profit");
        assertEquals(kwd("8.000"), after.cashBalance().subtract(before.cashBalance()), "cash +10 −2");
        assertEquals(kwd("8.500"), after.receivables().subtract(before.receivables()), "customer debts");
        assertEquals(kwd("5.000"), after.payables().subtract(before.payables()), "supplier payables");
        assertEquals(1, after.lowStockProducts() - before.lowStockProducts(), "low stock count");

        // --- lists -------------------------------------------------------------
        assertEquals(tag, dao.findRecentInvoices(8).get(0).invoiceNo(), "newest invoice first");
        assertTrue(dao.findRecentInvoices(8).get(0).remainingAmount().compareTo(kwd("8.500")) == 0);
        assertTrue(dao.findLowStock(500).stream().anyMatch(i -> i.productCode().equals(tag)));
        assertTrue(dao.findTopProducts(500, 30).stream()
                .anyMatch(t -> t.productCode().equals(tag) && t.salesAmount().compareTo(kwd("19.000")) == 0));

        List<SalesPoint> days = dao.salesByDay(7);
        SalesPoint today = days.stream().filter(d -> d.period().equals(after.serverDate())).findFirst().orElseThrow();
        assertEquals(after.todaySales(), today.total(), "chart today == card today");
        assertTrue(dao.salesByMonth(12).stream().anyMatch(m -> m.period().equals(after.serverDate().withDayOfMonth(1))));
    }
}
