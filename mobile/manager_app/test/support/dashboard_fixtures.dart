// Test-only responses matching frozen Manager DTOs. Never imported by production.
import 'package:manager_app/features/auth/data/auth_repository.dart';
import 'package:manager_app/features/auth/state/auth_controller.dart';

import 'fakes.dart';

const dashboardPermissions = <String>[
  'DASHBOARD',
  'SALES_VIEW',
  'FINANCIAL_REPORTS',
  'REPORTS_VIEW',
  'REPORTS_SALES',
  'REPORTS_PROFIT',
  'EXPENSES',
  'REPORTS_EXPENSES',
  'CASH',
  'REPORTS_CASHBOX',
  'INVENTORY',
  'REPORTS_INVENTORY',
  'PRODUCT_COST',
  'CUSTOMER_PAYMENTS',
  'CUSTOMERS_VIEW',
  'CUSTOMER_BALANCE_VIEW',
  'SUPPLIER_PAYMENTS',
  'SUPPLIERS_VIEW',
  'SUPPLIER_BALANCE_VIEW',
];
const businessDate = '2026-10-08';
Map<String, Object?> fixtureRange([
  String from = businessDate,
  String to = businessDate,
]) => {'from': from, 'to': to};
Map<String, Object?> fixtureOverview() => {
  'businessDate': businessDate,
  'metrics': <Map<String, Object?>>[
    {
      'name': 'TODAY_SALES',
      'value': '121.125',
      'gross': '125.250',
      'returns': '4.125',
    },
    {'name': 'TODAY_INVOICES', 'value': '2', 'gross': null, 'returns': null},
    {
      'name': 'MONTH_SALES',
      'value': '2500.500',
      'gross': '2510.500',
      'returns': '10.000',
    },
    {'name': 'NET_PROFIT', 'value': '111.987', 'gross': null, 'returns': null},
    {'name': 'EXPENSES', 'value': '50.000', 'gross': null, 'returns': null},
    {
      'name': 'CASH_BALANCE',
      'value': '180.000',
      'gross': null,
      'returns': null,
    },
    {'name': 'RECEIVABLES', 'value': '444.987', 'gross': null, 'returns': null},
    {'name': 'PAYABLES', 'value': '445.987', 'gross': null, 'returns': null},
    {'name': 'LOW_STOCK', 'value': '1', 'gross': null, 'returns': null},
  ],
};
Map<String, Object?> fixtureSales([Map<String, Object?>? range]) => {
  'range': range ?? fixtureRange(),
  'grossSales': '9007199254740994.250',
  'returns': '1.125',
  'netSales': '9007199254740993.125',
  'invoiceCount': 2,
  'returnCount': 1,
  'averageInvoice': '4503599627370497.125',
  'profit': {
    'historicalCost': '333.987',
    'returnedHistoricalCost': '3.000',
    'grossProfitAfterReturns': '556.987',
    'expenses': '1.000',
    'netProfit': '555.987',
  },
};
Map<String, Object?> fixtureExpenses([Map<String, Object?>? range]) => {
  'range': range ?? fixtureRange(),
  'total': '10.125',
  'count': 1,
  'categories': [
    {'category': 'RENT', 'amount': '10.125', 'count': 1},
  ],
};
Map<String, Object?> fixtureCash([Map<String, Object?>? range]) => {
  'range': range ?? fixtureRange(),
  'openingBalance': '100.000',
  'totalIn': '20.125',
  'totalOut': '5.125',
  'netMovement': '15.000',
  'closingBalance': '115.000',
};
Map<String, Object?> fixtureInventory() => {
  'activeProducts': 3,
  'lowStockProducts': 1,
  'outOfStockProducts': 0,
  'inventoryValue': '999.987',
};
Map<String, Object?> fixtureDaily() => {
  'date': businessDate,
  'sales': fixtureSales(),
  'expenses': fixtureExpenses(),
  'cashbox': fixtureCash(),
  'inventorySnapshot': {
    'businessDate': businessDate,
    'inventory': fixtureInventory(),
  },
};
Map<String, Object?> fixtureTop([Map<String, Object?>? range]) => {
  'range': range ?? fixtureRange(),
  'limit': 5,
  'items': [
    {
      'code': 'P-1',
      'name': 'صنف الاختبار باسم عربي طويل لقياس العرض',
      'unit': 'قطعة',
      'grossQuantity': '2.500',
      'returnedQuantity': '1.000',
      'netQuantity': '1.500',
      'netRevenue': '4.125',
      'grossProfit': '222.987',
    },
  ],
};
Map<String, Object?> fixturePage(List<Object?> items) => {
  'items': items,
  'page': 0,
  'size': 5,
  'totalItems': items.length,
  'totalPages': items.isEmpty ? 0 : 1,
};
Map<String, Object?> fixtureSlow() => fixturePage([
  {
    'code': 'S-1',
    'name': 'صنف بطيء الحركة',
    'unit': 'متر',
    'quantity': '1.125',
    'lastSale': null,
    'daysSinceLastSale': null,
    'inventoryValue': '888.987',
  },
]);
Map<String, Object?> fixtureLow() => fixturePage([
  {
    'id': 1,
    'code': 'L-1',
    'name': 'صنف منخفض المخزون',
    'unit': 'قطعة',
    'quantity': '0.125',
    'minimumStock': '1.500',
    'purchaseCost': '333.987',
  },
]);
Object? dashboardResponse(String path) {
  final uri = Uri.parse(path), q = Uri.parse(path).queryParameters;
  final range = fixtureRange(
    q['from'] ?? businessDate,
    q['to'] ?? businessDate,
  );
  return switch (uri.path) {
    'manager/dashboard' => fixtureOverview(),
    'manager/daily-summary' => fixtureDaily(),
    'manager/sales/summary' => fixtureSales(range),
    'manager/expenses/summary' => fixtureExpenses(range),
    'manager/cashbox/summary' => fixtureCash(range),
    'manager/sales/top-products' => fixtureTop(range),
    'manager/sales/slow-products' => fixtureSlow(),
    'manager/inventory/low-stock' => fixtureLow(),
    'manager/sales/trend' => {
      'range': range,
      'grouping': 'daily',
      'buckets': [
        for (
          var day = DateTime.parse('${range['from']}T00:00:00Z');
          !day.isAfter(DateTime.parse('${range['to']}T00:00:00Z'));
          day = day.add(const Duration(days: 1))
        )
          {
            'bucket': day.toIso8601String().substring(0, 10),
            'grossSales': '125.250',
            'returns': '4.125',
            'netSales': '121.125',
            'invoiceCount': 2,
          },
      ],
    },
    _ => null,
  };
}

Future<({AuthController auth, FakeTransport transport})> dashboardAuth({
  List<String> permissions = dashboardPermissions,
  bool restricted = false,
}) async {
  final transport = FakeTransport();
  final auth = AuthController(AuthRepository(transport), MemoryVault());
  transport.handler = (_, path, _, _) async => switch (path) {
    'auth/login' => {
      ...loginJson(restricted: restricted),
      'user': userJson(restricted: restricted, permissions: permissions),
    },
    'auth/me' => userJson(restricted: restricted, permissions: permissions),
    'auth/sessions' => [sessionJson(current: true)],
    _ => dashboardResponse(path),
  };
  await auth.login('fixture', 'fixture-password');
  return (auth: auth, transport: transport);
}
