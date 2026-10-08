import '../../auth/data/auth_models.dart';

/// Mirrors released permission intersections; role names never grant access.
class DashboardAccess {
  DashboardAccess(CurrentUser user) : permissions = user.permissions;
  final Set<String> permissions;
  bool has(String value) => permissions.contains(value);
  bool all(Set<String> values) => values.every(has);
  bool get dashboard => has('DASHBOARD');
  bool get sales => all({'REPORTS_VIEW', 'REPORTS_SALES'});
  bool get expenses => all({'REPORTS_VIEW', 'REPORTS_EXPENSES'});
  bool get cash => all({'CASH', 'REPORTS_VIEW', 'REPORTS_CASHBOX'});
  bool get inventory => all({'INVENTORY', 'REPORTS_VIEW', 'REPORTS_INVENTORY'});
  bool get profit => has('REPORTS_PROFIT');
  bool get cost => has('PRODUCT_COST');
  bool get periods => sales || expenses || cash;
  bool metric(String name) => switch (name) {
    'TODAY_SALES' ||
    'TODAY_INVOICES' => has('SALES_VIEW') || has('FINANCIAL_REPORTS'),
    'MONTH_SALES' => has('FINANCIAL_REPORTS'),
    'NET_PROFIT' => has('FINANCIAL_REPORTS') && profit,
    'EXPENSES' => has('EXPENSES'),
    'CASH_BALANCE' => has('CASH'),
    'RECEIVABLES' => all({
      'CUSTOMER_PAYMENTS',
      'CUSTOMERS_VIEW',
      'CUSTOMER_BALANCE_VIEW',
    }),
    'PAYABLES' => all({
      'SUPPLIER_PAYMENTS',
      'SUPPLIERS_VIEW',
      'SUPPLIER_BALANCE_VIEW',
    }),
    'LOW_STOCK' => has('INVENTORY') || has('PRODUCTS'),
    _ => false,
  };
}
