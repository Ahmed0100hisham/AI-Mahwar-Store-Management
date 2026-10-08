// Frozen response fixtures for tests only; production never imports this file.
import 'package:manager_app/features/auth/state/auth_controller.dart';

import 'dashboard_fixtures.dart';
import 'fakes.dart';

const inventoryPermissions = [
  ...dashboardPermissions,
  'PRODUCTS_VIEW',
  'PRODUCTS',
];
Map<String, Object?> productJson([int id = 1]) => {
  'id': id,
  'code': 'P-$id',
  'barcode': '00123456789$id',
  'nameAr': id == 3
      ? 'منتج عربي باسم طويل لاختبار عرض المخزون والكميات الكبيرة'
      : 'منتج $id',
  'nameEn': null,
  'category': 'أدوات',
  'brand': null,
  'unit': 'قطعة',
  'size': null,
  'color': null,
  'quantity': id == 1
      ? '2.125'
      : id == 2
      ? '0.000'
      : '9007199254740993.125',
  'minimumStock': '3.000',
  'salePrice': '1250.500',
  'wholesalePrice': '1200.000',
  'purchasePrice': '777.987',
  'active': true,
};
Map<String, Object?> detailJson([int id = 1]) => {
  ...productJson(id),
  'lowStock': id != 3,
  'purchaseCost': '777.987',
};
Map<String, Object?> lowJson([int id = 1]) => {
  'id': id,
  'code': 'P-$id',
  'name': 'منتج $id',
  'unit': 'قطعة',
  'quantity': id == 1 ? '2.125' : '0.000',
  'minimumStock': '3.000',
  'purchaseCost': '777.987',
};
Map<String, Object?> movementJson([int index = 0]) => {
  'date': '2026-10-07T13:00:00.125',
  'type': 'ADJUSTMENT_OUT',
  'typeName': 'تسوية صرف',
  'quantity': '-2.125',
  'before': '6.250',
  'after': '4.125',
  'unitCost': '778.987',
};
Map<String, Object?> inventoryPage(
  List<Object?> items, {
  int page = 0,
  int size = 20,
  int? total,
}) => {
  'items': items,
  'page': page,
  'size': size,
  'totalItems': total ?? items.length,
  'totalPages': ((total ?? items.length) + size - 1) ~/ size,
};
Object? inventoryResponse(String path) {
  final uri = Uri.parse(path), q = Uri.parse(path).queryParameters;
  final page = int.parse(q['page'] ?? '0'), size = int.parse(q['size'] ?? '20');
  if (uri.path == 'products' ||
      uri.path == 'manager/inventory/low-stock' ||
      uri.path.endsWith('/movements')) {
    final values = uri.path == 'products'
        ? [for (var id = 1; id <= 3; id++) productJson(id)]
        : uri.path.endsWith('/movements')
        ? [movementJson(), movementJson(), movementJson()]
        : [lowJson(1), lowJson(2)];
    return inventoryPage(
      values.skip(page * size).take(size).toList(),
      page: page,
      size: size,
      total: values.length,
    );
  }
  final match = RegExp(r'^manager/products/(\d+)$').firstMatch(uri.path);
  if (match != null) return detailJson(int.parse(match[1]!));
  return dashboardResponse(path);
}

Future<({AuthController auth, FakeTransport transport})> inventoryAuth({
  List<String> permissions = inventoryPermissions,
  bool restricted = false,
}) async {
  final fixture = await dashboardAuth(
    permissions: permissions,
    restricted: restricted,
  );
  fixture.transport.handler = (_, path, _, _) async => switch (path) {
    'auth/me' => userJson(permissions: permissions, restricted: restricted),
    'auth/sessions' => [sessionJson(current: true)],
    _ => inventoryResponse(path),
  };
  return fixture;
}
