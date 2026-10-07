package com.almahwar.api.manager;

import com.almahwar.api.web.PageResponse;
import com.almahwar.model.PartyType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static com.almahwar.api.manager.ManagerResponses.*;

/** Manager business endpoints are GET-only; permission checks are repeated by the query service. */
@RestController
@RequestMapping("/api/v1/manager")
@SecurityRequirement(name="bearer")
@Tag(name="Manager reads",description="Exact KWD and quantity strings (3 decimals). Inclusive business dates follow the SQL server's Kuwait accounting clock. Lists: page 0..10000, size 1..100 (default 20). Authenticated responses are no-store. Cost/profit/balance fields are omitted without their released permissions.")
public class ManagerController {
    private final ManagerQueryService service;
    public ManagerController(ManagerQueryService service) { this.service=service; }
    @GetMapping("/dashboard") @PreAuthorize("@managerAccess.allowed('dashboard')")
    @Operation(summary="Management dashboard",description="DASHBOARD. Permission-filtered Desktop metrics; month profit/expenses are explicitly monthly. Debt additionally needs BALANCE_VIEW.")
    public Dashboard dashboard() { return service.dashboard(); }
    @GetMapping("/sales/summary") @PreAuthorize("@managerAccess.allowed('sales')")
    @Operation(summary="Sales summary",description="REPORTS_VIEW + REPORTS_SALES. Maximum 366 inclusive days. POSTED sales minus returns by their own date. Average is gross / invoice count. Profit only with REPORTS_PROFIT, using historical cost.")
    public Sales sales(@ParameterObject @ModelAttribute ManagerDateRange.Input range) { return service.sales(range); }
    @GetMapping("/sales/trend") @PreAuthorize("@managerAccess.allowed('sales')")
    @Operation(summary="Chronological sales trend",description="Sales permissions. daily: maximum 366 days; weekly/monthly: 1096 days. Sunday weeks. Zero-filled buckets; edge buckets cover only the requested dates.")
    public Trend trend(@ParameterObject @ModelAttribute ManagerDateRange.Input range,@RequestParam(defaultValue="daily") String grouping) { return service.trend(range,grouping); }
    @GetMapping("/sales/top-products") @PreAuthorize("@managerAccess.allowed('sales')")
    @Operation(summary="Top products by net quantity",description="Sales permissions. Maximum 366 days, limit 1..100. Released report accounts for returns and invoice discounts. Profit only with REPORTS_PROFIT.")
    public TopProducts top(@ParameterObject @ModelAttribute ManagerDateRange.Input range,@RequestParam(defaultValue="20") int limit) { return service.top(range,limit); }
    @GetMapping("/sales/slow-products") @PreAuthorize("@managerAccess.allowed('inventory')")
    @Operation(summary="Slow-moving stock",description="INVENTORY + REPORTS_VIEW + REPORTS_INVENTORY. Active stocked products not sold since days (1..3650), or never sold. Paged, literal search; sort lastSale,asc only. Value only with PRODUCT_COST.")
    public PageResponse<SlowProduct> slow(@RequestParam(defaultValue="30") int days,@ParameterObject @ModelAttribute ManagerPage page) { return service.slow(days,page); }
    @GetMapping("/expenses/summary") @PreAuthorize("@managerAccess.allowed('expenses')")
    @Operation(summary="Expense summary",description="REPORTS_VIEW + REPORTS_EXPENSES. Maximum 366 days; recorded amounts grouped by released categories.")
    public Expenses expenses(@ParameterObject @ModelAttribute ManagerDateRange.Input range) { return service.expenses(range); }
    @GetMapping("/cashbox/summary") @PreAuthorize("@managerAccess.allowed('cashbox')")
    @Operation(summary="Cash book summary",description="CASH + REPORTS_VIEW + REPORTS_CASHBOX. Maximum 366 days. All payment methods; opening before range, IN/OUT during range and closing.")
    public Cashbox cashbox(@ParameterObject @ModelAttribute ManagerDateRange.Input range) { return service.cashbox(range); }
    @GetMapping("/inventory/summary") @PreAuthorize("@managerAccess.allowed('inventory')")
    @Operation(summary="Current inventory",description="INVENTORY + REPORTS_VIEW + REPORTS_INVENTORY. Active products; low quantity <= minimum, out quantity <= 0. Current purchase-cost valuation only with PRODUCT_COST. Mixed units are not summed.")
    public Inventory inventory() { return service.inventory(); }
    @GetMapping("/inventory/low-stock") @PreAuthorize("@managerAccess.allowed('inventory')")
    @Operation(summary="Low stock page",description="Inventory permissions. Active products at/below minimum. Sort name, code, quantity (default), minimumStock with asc/desc; literal q. Cost only with PRODUCT_COST.")
    public PageResponse<StockProduct> lowStock(@ParameterObject @ModelAttribute ManagerPage page) { return service.lowStock(page); }
    @GetMapping("/products/{id}") @PreAuthorize("@managerAccess.allowed('products')")
    @Operation(summary="Product detail",description="PRODUCTS_VIEW or PRODUCTS. Inactive products only with PRODUCTS; cost only with PRODUCT_COST. Existing /api/v1/products contract is preserved.")
    public ProductDetail product(@PathVariable int id) { return service.product(id); }
    @GetMapping("/products/{id}/movements") @PreAuthorize("@managerAccess.allowed('inventory')")
    @Operation(summary="Product stock history",description="Inventory permissions. Maximum 366 days, paged; fixed date,desc. Signed quantity, before/after; unit cost only with PRODUCT_COST.")
    public PageResponse<Movement> movements(@PathVariable int id,@ParameterObject @ModelAttribute ManagerDateRange.Input range,@ParameterObject @ModelAttribute ManagerPage page) { return service.movements(id,range,page); }
    @GetMapping("/customers") @PreAuthorize("@managerAccess.allowed('customers')")
    @Operation(summary="Customer page",description="CUSTOMERS_VIEW. Literal q; sort name (default), code, balance with asc/desc. Balance fields and sorting require CUSTOMER_BALANCE_VIEW. Includes active/inactive profiles.")
    public PartyList customers(@ParameterObject @ModelAttribute ManagerPage page) { return service.parties(PartyType.CUSTOMER,page,false); }
    @GetMapping("/customers/receivables") @PreAuthorize("@managerAccess.allowed('customerAccounts')")
    @Operation(summary="Customer receivables",description="CUSTOMERS_VIEW + CUSTOMER_BALANCE_VIEW. Positive ledger debit-credit only; includes inactive debtors. Paged/literal q; sort name/code/balance (default balance,desc); totalOutstanding covers every match.")
    public PartyList receivables(@ParameterObject @ModelAttribute ManagerPage page) { return service.parties(PartyType.CUSTOMER,page,true); }
    @GetMapping("/customers/{id}") @PreAuthorize("@managerAccess.allowed('customers')")
    @Operation(summary="Customer detail",description="CUSTOMERS_VIEW; cached current balance only with CUSTOMER_BALANCE_VIEW. Minimal identity/contact projection.")
    public Party customer(@PathVariable int id) { return service.party(PartyType.CUSTOMER,id); }
    @GetMapping("/customers/{id}/account") @PreAuthorize("@managerAccess.allowed('customerAccounts')")
    @Operation(summary="Customer account statement",description="CUSTOMERS_VIEW + CUSTOMER_BALANCE_VIEW. Maximum 366 days; paged, fixed date,asc. Opening before range; running balances include whole history. No q.")
    public Account customerAccount(@PathVariable int id,@ParameterObject @ModelAttribute ManagerDateRange.Input range,@ParameterObject @ModelAttribute ManagerPage page) { return service.account(PartyType.CUSTOMER,id,range,page); }
    @GetMapping("/suppliers") @PreAuthorize("@managerAccess.allowed('suppliers')")
    @Operation(summary="Supplier page",description="SUPPLIERS_VIEW. Literal q; sort name/code/balance. Balance fields/sort require SUPPLIER_BALANCE_VIEW. Includes inactive profiles.")
    public PartyList suppliers(@ParameterObject @ModelAttribute ManagerPage page) { return service.parties(PartyType.SUPPLIER,page,false); }
    @GetMapping("/suppliers/payables") @PreAuthorize("@managerAccess.allowed('supplierAccounts')")
    @Operation(summary="Supplier payables",description="SUPPLIERS_VIEW + SUPPLIER_BALANCE_VIEW. Positive ledger credit-debit only; includes inactive creditors. Paged/literal q; sort name/code/balance, default balance,desc.")
    public PartyList payables(@ParameterObject @ModelAttribute ManagerPage page) { return service.parties(PartyType.SUPPLIER,page,true); }
    @GetMapping("/suppliers/{id}") @PreAuthorize("@managerAccess.allowed('suppliers')")
    @Operation(summary="Supplier detail",description="SUPPLIERS_VIEW; cached balance only with SUPPLIER_BALANCE_VIEW. Minimal identity/contact projection.")
    public Party supplier(@PathVariable int id) { return service.party(PartyType.SUPPLIER,id); }
    @GetMapping("/suppliers/{id}/account") @PreAuthorize("@managerAccess.allowed('supplierAccounts')")
    @Operation(summary="Supplier account statement",description="SUPPLIERS_VIEW + SUPPLIER_BALANCE_VIEW. Maximum 366 days; paged date,asc. Positive balance = we owe supplier; running effect credit-debit. No q.")
    public Account supplierAccount(@PathVariable int id,@ParameterObject @ModelAttribute ManagerDateRange.Input range,@ParameterObject @ModelAttribute ManagerPage page) { return service.account(PartyType.SUPPLIER,id,range,page); }
}
