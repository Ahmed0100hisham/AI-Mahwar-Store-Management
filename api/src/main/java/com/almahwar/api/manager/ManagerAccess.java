package com.almahwar.api.manager;

import com.almahwar.api.core.SpringSecurityContext;
import com.almahwar.service.AccessDeniedException;
import org.springframework.stereotype.Component;
import static com.almahwar.model.Permission.*;

/** Endpoint and direct-service checks use the released permission matrix, never role names. */
@Component("managerAccess")
public class ManagerAccess {
    private final SpringSecurityContext security;
    public ManagerAccess(SpringSecurityContext security) { this.security = security; }
    public boolean allowed(String view) {
        return switch (view) {
            case "dashboard" -> security.hasPermission(DASHBOARD);
            case "invoices" -> security.hasPermission(SALES_VIEW);
            case "quotations" -> security.hasPermission(QUOTATIONS_VIEW);
            case "sales" -> security.hasPermission(REPORTS_VIEW) && security.hasPermission(REPORTS_SALES);
            case "inventory" -> security.hasPermission(INVENTORY) && security.hasPermission(REPORTS_VIEW)
                    && security.hasPermission(REPORTS_INVENTORY);
            case "expenses" -> security.hasPermission(REPORTS_VIEW) && security.hasPermission(REPORTS_EXPENSES);
            case "cashbox" -> security.hasPermission(CASH) && security.hasPermission(REPORTS_VIEW)
                    && security.hasPermission(REPORTS_CASHBOX);
            case "products" -> security.hasPermission(PRODUCTS_VIEW) || security.hasPermission(PRODUCTS);
            case "customers" -> security.hasPermission(CUSTOMERS_VIEW);
            case "suppliers" -> security.hasPermission(SUPPLIERS_VIEW);
            case "customerAccounts" -> allowed("customers") && security.hasPermission(CUSTOMER_BALANCE_VIEW);
            case "supplierAccounts" -> allowed("suppliers") && security.hasPermission(SUPPLIER_BALANCE_VIEW);
            default -> false;
        };
    }
    public void require(String view) {
        if (!allowed(view)) throw new AccessDeniedException("ليس لديك صلاحية عرض هذه البيانات.");
    }
    public boolean cost() { return security.hasPermission(PRODUCT_COST); }
    public boolean profit() { return security.hasPermission(REPORTS_PROFIT); }
}
