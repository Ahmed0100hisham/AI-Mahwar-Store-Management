// Synthetic frozen-contract responses only. Production never imports fixtures.
import 'package:manager_app/features/auth/data/auth_repository.dart';
import 'package:manager_app/features/auth/state/auth_controller.dart';

import 'dashboard_fixtures.dart';
import 'fakes.dart';
import 'inventory_fixtures.dart';
import 'party_fixtures.dart';

const finalPermissions = [
  ...partyPermissions,
  'QUOTATIONS_VIEW',
  'AUDIT_LOG',
  'USERS_VIEW',
  'USERS_CREATE',
  'USERS_EDIT',
  'USERS_RESET_PASSWORD',
];
Map<String, Object?> finalUser({
  List<String> permissions = finalPermissions,
  String role = 'ADMIN',
  bool restricted = false,
}) => {
  ...userJson(permissions: permissions, restricted: restricted),
  'roleCode': role,
  'roleName': role,
};
Map<String, Object?> quotationJson({
  int id = 1,
  String status = 'ACCEPTED',
  bool prospect = false,
}) => {
  'id': id,
  'number': 'Q-2026-00000000000000000-$id',
  'date': '2026-10-08T12:00:00',
  'validUntil': '2026-10-07',
  'businessDate': businessDate,
  'pastValidity': true,
  'status': status,
  'customer': {
    'id': prospect ? null : 2,
    'code': prospect ? null : 'C-2',
    'name': prospect ? null : 'عميل العرض',
    'phone': null,
  },
  'prospectName': prospect ? 'عميل محتمل' : null,
  'prospectPhone': null,
  'creator': {'id': 1, 'name': 'محرّر العرض'},
  'priceType': 'RETAIL',
  'subtotal': '9007199254740994.250',
  'discount': '1.125',
  'total': '9007199254740993.125',
  'sentAt': '2026-10-07T12:00:00',
  'decidedAt': null,
  'linkedSale': {'id': 1, 'number': 'INV-1', 'status': 'DRAFT'},
};
Map<String, Object?> quotationLine([int id = 1]) => {
  'id': id,
  'productId': 1,
  'productCode': 'P-1',
  'name': 'منتج عرض السعر',
  'unit': 'قطعة',
  'quantity': '2.500',
  'unitPrice': '4.125',
  'discount': '1.125',
  'total': '9.187',
};
Map<String, Object?> adminUser([int id = 2]) => {
  'id': id,
  'username': 'user$id',
  'fullName': 'مستخدم الاختبار $id',
  'phone': null,
  'email': null,
  'roleCode': 'CASHIER',
  'roleName': 'كاشير',
  'active': true,
  'mustChangePassword': true,
  'createdAt': '2026-10-07T12:00:00',
  'lastLoginAt': null,
};
List<Object?> adminRoles() => [
  for (final r in ['ADMIN', 'ACCOUNTANT', 'CASHIER', 'STOREKEEPER'])
    {'code': r, 'name': r},
];
List<Object?> adminPermissions() => [
  {
    'code': 'USERS_VIEW',
    'description': 'عرض المستخدمين',
    'group': 'المستخدمون',
    'roles': ['ADMIN'],
  },
];
Map<String, Object?> auditEvent([int id = 1]) => {
  'id': id,
  'timestamp': '2026-10-08T12:00:00',
  'userId': 1,
  'username': 'admin',
  'fullName': 'مسؤول الاختبار',
  'action': 'USER_CREATED',
  'category': 'Users',
};
Object? finalResponse(String path) {
  final uri = Uri.parse(path), q = Uri.parse(path).queryParameters;
  final page = int.parse(q['page'] ?? '0'), size = int.parse(q['size'] ?? '20');
  if (uri.path == 'manager/quotations') {
    return inventoryPage(
      page == 0 ? [quotationJson()] : [],
      page: page,
      size: size,
      total: 1,
    );
  }
  if (uri.path.startsWith('manager/quotations/')) {
    return {
      'quotation': quotationJson(),
      'items': inventoryPage(
        page == 0 ? [quotationLine()] : [],
        page: page,
        size: size,
        total: 1,
      ),
    };
  }
  if (uri.path == 'manager/admin/roles') return adminRoles();
  if (uri.path == 'manager/admin/permissions') return adminPermissions();
  if (uri.path == 'manager/admin/users') {
    return inventoryPage(
      page == 0 ? [adminUser()] : [],
      page: page,
      size: size,
      total: 1,
    );
  }
  if (uri.path.startsWith('manager/admin/users/')) {
    return adminUser(int.parse(uri.path.split('/')[3]));
  }
  if (uri.path == 'manager/audit') {
    return inventoryPage(
      page == 0 ? [auditEvent()] : [],
      page: page,
      size: size,
      total: 1,
    );
  }
  if (uri.path == 'manager/expenses/summary') return fixtureExpenses();
  if (uri.path == 'manager/cashbox/summary') return fixtureCash();
  if (uri.path == 'manager/daily-summary') return fixtureDaily();
  if (uri.path == 'manager/inventory/summary') return fixtureInventory();
  if (uri.path == 'manager/sales/slow-products') {
    return {...fixtureSlow(), 'page': page, 'size': size, 'totalPages': 1};
  }
  return partyResponse(path);
}

Future<({AuthController auth, FakeTransport transport})> finalAuth({
  List<String> permissions = finalPermissions,
  String role = 'ADMIN',
}) async {
  final transport = FakeTransport();
  transport.handler = (method, path, body, token) async {
    if (path == 'auth/login' || path == 'auth/refresh') {
      return {
        ...loginJson(),
        'user': finalUser(permissions: permissions, role: role),
      };
    }
    if (path == 'auth/me') {
      return finalUser(permissions: permissions, role: role);
    }
    if (path.startsWith('auth/')) return null;
    return finalResponse(path);
  };
  final auth = AuthController(AuthRepository(transport), MemoryVault());
  await auth.login('fixture', 'synthetic-fixture');
  transport.calls.clear();
  return (auth: auth, transport: transport);
}
