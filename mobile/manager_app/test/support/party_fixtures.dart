// Synthetic contract fixtures only; never imported by production.
import 'package:manager_app/features/auth/state/auth_controller.dart';
import 'package:manager_app/features/dashboard/data/dashboard_models.dart';
import 'package:manager_app/features/parties/data/party_access.dart';

import 'dashboard_fixtures.dart';
import 'fakes.dart';
import 'inventory_fixtures.dart';
import 'sales_fixtures.dart';

const partyPermissions = [
  ...salesPermissions,
  'CUSTOMERS_VIEW',
  'CUSTOMER_BALANCE_VIEW',
  'SUPPLIERS_VIEW',
  'SUPPLIER_BALANCE_VIEW',
];
Map<String, Object?> partyJson(PartyKind kind, int id) => {
  'id': id,
  'code': '${kind == PartyKind.customer ? 'C' : 'S'}-$id',
  'name': '${kind == PartyKind.customer ? 'عميل' : 'مورد'} الاختبار $id',
  'phone': id == 1 ? '12345678' : null,
  'area': id == 1 ? 'الكويت' : null,
  'active': id != 2,
  'balance': kind == PartyKind.customer
      ? (id == 1
            ? '8.875'
            : id == 2
            ? '20.000'
            : '-2.000')
      : (id == 1
            ? '7.000'
            : id == 2
            ? '4.000'
            : '-1.000'),
};
Map<String, Object?> partyEntry(PartyKind kind, int index) => {
  'date':
      '2026-10-${index == 1
          ? '01'
          : index == 2
          ? '03'
          : '07'}T12:00:00',
  'type': kind == PartyKind.customer
      ? (index == 1
            ? 'SALE'
            : index == 2
            ? 'SALE_RETURN'
            : 'PAYMENT')
      : (index == 1 ? 'PURCHASE' : 'PAYMENT'),
  'reference': index == 1 ? 'REF-2026-000000000000000000000000000001' : null,
  'debit': kind == PartyKind.customer
      ? (index == 1 ? '10.000' : '0.000')
      : (index == 1 ? '0.000' : '10.000'),
  'credit': kind == PartyKind.customer
      ? (index == 1
            ? '0.000'
            : index == 2
            ? '2.500'
            : '3.625')
      : (index == 1 ? '5.000' : '0.000'),
  'runningBalance': kind == PartyKind.customer
      ? (index == 1
            ? '15.000'
            : index == 2
            ? '12.500'
            : '8.875')
      : (index == 1 ? '17.000' : '7.000'),
};
Map<String, Object?> partyAccountJson(
  PartyKind kind, {
  int id = 1,
  int page = 0,
  int size = 20,
  String from = '2026-10-01',
  String to = '2026-10-31',
}) {
  final entries =
      [
        for (var i = 1; i <= (kind == PartyKind.customer ? 3 : 2); i++)
          partyEntry(kind, i),
      ].where((row) {
        final date = (row['date'] as String).substring(0, 10);
        return date.compareTo(from) >= 0 && date.compareTo(to) <= 0;
      }).toList();
  return {
    'partyId': id,
    'range': fixtureRange(from, to),
    'openingBalance': kind == PartyKind.customer ? '5.000' : '12.000',
    'totalDebit': kind == PartyKind.customer ? '10.000' : '10.000',
    'totalCredit': kind == PartyKind.customer ? '6.125' : '5.000',
    'closingBalance': kind == PartyKind.customer ? '8.875' : '7.000',
    'entries': inventoryPage(
      entries.skip(page * size).take(size).toList(),
      page: page,
      size: size,
      total: entries.length,
    ),
  };
}

Object? partyResponse(String path) {
  final uri = Uri.parse(path), q = uri.queryParameters;
  for (final kind in PartyKind.values) {
    final base = 'manager/${kind.path}';
    final page = int.parse(q['page'] ?? '0'),
        size = int.parse(q['size'] ?? '20');
    if (uri.path == base || uri.path == '$base/${kind.debtPath}') {
      final outstanding = uri.path != base;
      final search = q['q'] ?? '';
      final rows = [for (var id = 1; id <= 3; id++) partyJson(kind, id)]
          .where(
            (p) => !outstanding || !(p['balance'] as String).startsWith('-'),
          )
          .where(
            (p) =>
                search.isEmpty ||
                p['name'].toString().contains(search) ||
                p['code'].toString().contains(search),
          )
          .toList();
      if (q['sort'] == 'code,desc') {
        rows.sort(
          (a, b) => (b['code'] as String).compareTo(a['code'] as String),
        );
      }
      return {
        'page': inventoryPage(
          rows.skip(page * size).take(size).toList(),
          page: page,
          size: size,
          total: rows.length,
        ),
        if (outstanding)
          'totalOutstanding': kind == PartyKind.customer ? '28.875' : '11.000',
      };
    }
    final detail = RegExp('^$base/(\\d+)(/account)?\$').firstMatch(uri.path);
    if (detail != null) {
      final id = int.parse(detail[1]!);
      if (detail[2] == null) return partyJson(kind, id);
      final period = switch (q['period']) {
        'today' => DashboardPeriod.today,
        'this_week' => DashboardPeriod.thisWeek,
        _ => DashboardPeriod.thisMonth,
      };
      final range = period.range(BusinessDay.parse(businessDate));
      return partyAccountJson(
        kind,
        id: id,
        page: page,
        size: size,
        from: q['from'] ?? range.from.iso,
        to: q['to'] ?? range.to.iso,
      );
    }
  }
  return salesResponse(path);
}

Future<({AuthController auth, FakeTransport transport})> partyAuth({
  List<String> permissions = partyPermissions,
  bool restricted = false,
}) async {
  final f = await salesAuth(permissions: permissions, restricted: restricted);
  f.transport.handler = (_, p, _, _) async => switch (p) {
    'auth/me' => userJson(permissions: permissions, restricted: restricted),
    'auth/sessions' => [sessionJson(current: true)],
    _ => partyResponse(p),
  };
  return f;
}
