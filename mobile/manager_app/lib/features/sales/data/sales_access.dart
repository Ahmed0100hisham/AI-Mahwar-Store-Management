import '../../auth/data/auth_models.dart';

class SalesAccess {
  SalesAccess(this.user);
  final CurrentUser? user;
  bool has(String code) => user?.allows(code) ?? false;
  bool get reports => has('REPORTS_VIEW') && has('REPORTS_SALES');
  bool get invoices => has('SALES_VIEW');
  bool get enter => reports || invoices;
  bool get reportProfit => has('REPORTS_PROFIT');
  bool get invoiceCost => has('SALES_COST_VIEW');
  bool get invoiceProfit => has('SALES_PROFIT_VIEW');
}
