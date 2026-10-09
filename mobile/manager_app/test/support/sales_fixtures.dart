// Synthetic transport fixtures used only by tests, never production.
import 'package:manager_app/features/auth/state/auth_controller.dart';
import 'package:manager_app/features/dashboard/data/dashboard_models.dart';

import 'dashboard_fixtures.dart';
import 'inventory_fixtures.dart';
import 'fakes.dart';

const salesPermissions = [
  ...inventoryPermissions,
  'SALES_COST_VIEW',
  'SALES_PROFIT_VIEW',
];
Map<String, Object?> invoiceJson(int id, {bool detail = false}) => {
  'id': id,
  'number': id == 3
      ? 'INV-2026-IDENTIFIER-000000000000000000000000003'
      : 'INV-$id',
  'date': '2026-10-01T00:00:00',
  'customer': {'id': 1, 'code': 'C-1', 'name': 'عميل الاختبار', 'phone': null},
  'creator': {'id': 1, 'name': 'منشئ الفاتورة'},
  'status': id == 2
      ? 'DRAFT'
      : id == 3
      ? 'CANCELLED'
      : 'POSTED',
  'priceType': 'RETAIL',
  'paymentMethod': 'MIXED',
  'paymentStatus': 'PARTIAL',
  'subtotal': '10.000',
  'discount': '1.125',
  'tax': '2.500',
  'total': id == 3 ? '9007199254740993.125' : '11.375',
  'paid': '5.000',
  'remaining': '6.375',
  'returnedAmount': detail ? '9.000' : '3.125',
  'refundedAmount': '2.000',
  'netAmount': detail ? '2.375' : '8.250',
  if (!detail) 'returnCutoffDate': '2026-10-31',
  'historicalCost': '7.987',
  'grossProfit': '3.388',
};
Map<String, Object?> invoiceLineJson([int id = 1]) => {
  'id': id,
  'productId': id,
  'productCode': 'P-$id',
  'name': id == 3
      ? 'منتج عربي طويل لاختبار البنود والقيم الدقيقة الكبيرة'
      : 'بند $id',
  'unit': 'قطعة',
  'quantity': id == 3 ? '9007199254740993.125' : '2.125',
  'unitPrice': '5.000',
  'discount': '1.125',
  'total': '9.500',
  'returnedQuantity': '0.125',
  'historicalUnitCost': '1.987',
};
Map<String, Object?> invoiceReturnJson([int id = 1]) => {
  'id': id,
  'number': 'RET-$id',
  'date': id == 3 ? '2026-11-05T12:00:00' : '2026-10-03T12:00:00',
  'total': '3.000',
  'refund': id == 3 ? '0.000' : '1.000',
  'refundMethod': 'CASH',
};
Map<String, Object?> detailInvoiceJson({
  int id = 1,
  int page = 0,
  int size = 20,
  int returnsPage = 0,
  int returnsSize = 20,
}) => {
  'invoice': invoiceJson(id, detail: true),
  'items': inventoryPage(
    [for (var i = 1; i <= 3; i++) invoiceLineJson(i)]
        .skip(page * size)
        .take(size)
        .toList(),
    page: page,
    size: size,
    total: 3,
  ),
  'returns': inventoryPage(
    [for (var i = 1; i <= 3; i++) invoiceReturnJson(i)]
        .skip(returnsPage * returnsSize)
        .take(returnsSize)
        .toList(),
    page: returnsPage,
    size: returnsSize,
    total: 3,
  ),
};
Object? salesResponse(String path) {
  final uri = Uri.parse(path), q = uri.queryParameters;
  final period = q['period'] ?? 'today';
  final preset = switch (period) {
    'this_month' => DashboardPeriod.thisMonth,
    'this_week' => DashboardPeriod.thisWeek,
    _ => DashboardPeriod.today,
  };
  final range = q.containsKey('from')
      ? BusinessRange(BusinessDay.parse(q['from']), BusinessDay.parse(q['to']))
      : preset.range(BusinessDay.parse(businessDate));
  final from = range.from.iso, to = range.to.iso;
  if (uri.path == 'manager/sales/summary') {
    return fixtureSales(fixtureRange(from, to));
  }
  if (uri.path == 'manager/sales/trend' ||
      uri.path == 'manager/sales/top-products') {
    return dashboardResponse('${uri.path}?from=$from&to=$to');
  }
  final page = int.parse(q['page'] ?? '0'), size = int.parse(q['size'] ?? '20');
  if (uri.path == 'manager/invoices') {
    return inventoryPage(
      [
        for (var i = 1; i <= 3; i++)
          {...invoiceJson(i), 'returnCutoffDate': to},
      ].skip(page * size).take(size).toList(),
      page: page,
      size: size,
      total: 3,
    );
  }
  final detail = RegExp(r'^manager/invoices/(\d+)$').firstMatch(uri.path);
  if (detail != null) {
    return detailInvoiceJson(
      id: int.parse(detail[1]!),
      page: page,
      size: size,
      returnsPage: int.parse(q['returnsPage'] ?? '0'),
      returnsSize: int.parse(q['returnsSize'] ?? '20'),
    );
  }
  return inventoryResponse(path);
}

Future<({AuthController auth, FakeTransport transport})> salesAuth({
  List<String> permissions = salesPermissions,
  bool restricted = false,
}) async {
  final f = await inventoryAuth(
    permissions: permissions,
    restricted: restricted,
  );
  f.transport.handler = (_, p, _, _) async => switch (p) {
    'auth/me' => userJson(permissions: permissions, restricted: restricted),
    'auth/sessions' => [sessionJson(current: true)],
    _ => salesResponse(p),
  };
  return f;
}
