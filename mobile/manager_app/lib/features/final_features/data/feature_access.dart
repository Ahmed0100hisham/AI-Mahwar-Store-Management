import '../../auth/data/auth_models.dart';
import '../../dashboard/data/dashboard_access.dart';

class FeatureAccess {
  FeatureAccess(this.user);
  final CurrentUser? user;
  bool has(String code) => user?.allows(code) ?? false;
  bool get quotations => has('QUOTATIONS_VIEW');
  bool get saleLinks => has('SALES_VIEW');
  bool get admin => user?.roleCode == 'ADMIN';
  bool get audit => admin && has('AUDIT_LOG');
  bool get users => admin && has('USERS_VIEW');
  bool get create => admin && has('USERS_CREATE');
  bool get edit => admin && has('USERS_EDIT');
  bool get reset => admin && has('USERS_RESET_PASSWORD');
  DashboardAccess? get analytics =>
      user == null ? null : DashboardAccess(user!);
  bool get reports =>
      user != null &&
      (analytics!.sales ||
          analytics!.expenses ||
          analytics!.cash ||
          analytics!.inventory ||
          analytics!.dashboard ||
          (has('CUSTOMERS_VIEW') && has('CUSTOMER_BALANCE_VIEW')) ||
          (has('SUPPLIERS_VIEW') && has('SUPPLIER_BALANCE_VIEW')));
}
