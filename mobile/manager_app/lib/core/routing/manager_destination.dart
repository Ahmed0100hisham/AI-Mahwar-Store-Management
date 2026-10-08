import '../../features/auth/data/auth_models.dart';

class ManagerDestination {
  const ManagerDestination(this.label, this.permissions, {this.any = false});
  final String label;
  final Set<String> permissions;
  final bool any;
  bool visibleTo(CurrentUser user) =>
      any ? permissions.any(user.allows) : permissions.every(user.allows);
  static const future = <ManagerDestination>[
    ManagerDestination('لوحة المتابعة', {'DASHBOARD'}),
    ManagerDestination('المبيعات', {'REPORTS_VIEW', 'REPORTS_SALES'}),
    ManagerDestination('المخزون', {
      'INVENTORY',
      'REPORTS_VIEW',
      'REPORTS_INVENTORY',
    }),
    ManagerDestination('العملاء', {'CUSTOMERS_VIEW'}),
    ManagerDestination('الموردون', {'SUPPLIERS_VIEW'}),
    ManagerDestination('الفواتير', {'SALES_VIEW'}),
    ManagerDestination('عروض الأسعار', {'QUOTATIONS_VIEW'}),
    ManagerDestination('سجل التدقيق', {'AUDIT_LOG'}),
    ManagerDestination('المستخدمون', {'USERS_VIEW'}),
  ];
}
