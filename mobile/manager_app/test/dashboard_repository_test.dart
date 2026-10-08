import 'package:flutter_test/flutter_test.dart';
import 'package:manager_app/core/errors/app_failure.dart';
import 'package:manager_app/core/utils/money.dart';
import 'package:manager_app/features/auth/data/auth_models.dart';
import 'package:manager_app/features/dashboard/data/dashboard_access.dart';
import 'package:manager_app/features/dashboard/data/dashboard_models.dart';
import 'package:manager_app/features/dashboard/data/dashboard_repository.dart';

import 'support/dashboard_fixtures.dart';
import 'support/fakes.dart';

void main() {
  DashboardAccess access([List<String> permissions = dashboardPermissions]) =>
      DashboardAccess(CurrentUser.fromJson(userJson(permissions: permissions)));
  test('exact decimal strings exceed floating point integer precision', () {
    final value = SalesSummary.fromJson(fixtureSales(), access());
    expect(value.netSales, '9007199254740993.125');
    expect(formatKwd(value.netSales), '9,007,199,254,740,993.125 د.ك');
    expect(
      formatQuantity('12345678901234567890.125'),
      '12,345,678,901,234,567,890.125',
    );
  });
  for (final value in ['0.000', '-2.500', '12.125', '999999999999999999.999']) {
    test('money and quantity preserve $value', () {
      expect(formatKwd(value), '${formatQuantity(value)} د.ك');
      expect(
        SalesSummary.fromJson({
          ...fixtureSales(),
          'netSales': value,
        }, access()).netSales,
        value,
      );
    });
  }
  for (final value in [null, 1.125, '1.25', '1e3', 'NaN', '1,000.000']) {
    test('malformed required decimal rejected: $value', () {
      expect(
        () => SalesSummary.fromJson({
          ...fixtureSales(),
          'netSales': value,
        }, access()),
        throwsA(AppFailure.malformed),
      );
    });
  }
  test('omitted profit is not zero, even with permission', () {
    final raw = fixtureSales()..remove('profit');
    expect(SalesSummary.fromJson(raw, access()).netProfit, isNull);
  });
  test('over-returned profit is discarded without permission', () {
    expect(
      SalesSummary.fromJson(
        fixtureSales(),
        access(['REPORTS_VIEW', 'REPORTS_SALES']),
      ).netProfit,
      isNull,
    );
    final raw = (fixtureTop()['items'] as List).first;
    expect(TopProduct.fromJson(raw, access([])).profit, isNull);
  });
  test('omitted inventory valuation stays absent', () {
    expect(
      InventorySummary.fromJson(
        fixtureInventory()..remove('inventoryValue'),
        access(),
      ).valueAtCost,
      isNull,
    );
  });
  test('over-returned inventory and slow stock valuations discarded', () {
    expect(
      InventorySummary.fromJson(fixtureInventory(), access([])).valueAtCost,
      isNull,
    );
    expect(
      SlowProduct.fromJson(
        (fixtureSlow()['items'] as List).first,
        access([]),
      ).valueAtCost,
      isNull,
    );
  });
  test('authorized sensitive null is malformed, not redaction', () {
    expect(
      () => InventorySummary.fromJson({
        ...fixtureInventory(),
        'inventoryValue': null,
      }, access()),
      throwsA(AppFailure.malformed),
    );
    expect(
      () =>
          SalesSummary.fromJson({...fixtureSales(), 'profit': null}, access()),
      throwsA(AppFailure.malformed),
    );
  });
  test('required counts and collections are strict', () {
    expect(
      () => InventorySummary.fromJson({
        ...fixtureInventory(),
        'activeProducts': 1.0,
      }, access()),
      throwsA(AppFailure.malformed),
    );
    expect(
      () => ExpenseSummary.fromJson({...fixtureExpenses(), 'categories': null}),
      throwsA(AppFailure.malformed),
    );
    expect(
      () =>
          DashboardOverview.fromJson({'businessDate': businessDate}, access()),
      throwsA(AppFailure.malformed),
    );
  });
  test('daily sections are discarded without full permission intersection', () {
    final daily = DailySummary.fromJson(
      fixtureDaily(),
      access(['DASHBOARD', 'REPORTS_SALES', 'PRODUCT_COST']),
    );
    expect(daily.sales, isNull);
    expect(daily.expenses, isNull);
    expect(daily.cash, isNull);
    expect(daily.inventory, isNull);
  });
  test(
    'balance metrics require view, balance and released dashboard permission',
    () {
      final overview = DashboardOverview.fromJson(
        fixtureOverview(),
        access([
          'DASHBOARD',
          'CUSTOMER_PAYMENTS',
          'CUSTOMERS_VIEW',
          'SUPPLIER_PAYMENTS',
          'SUPPLIERS_VIEW',
        ]),
      );
      expect(overview.metrics.containsKey('RECEIVABLES'), isFalse);
      expect(overview.metrics.containsKey('PAYABLES'), isFalse);
    },
  );
  test('unknown compatible metric ignored and duplicates rejected', () {
    final raw = fixtureOverview();
    (raw['metrics'] as List).add({
      'name': 'FUTURE_METRIC',
      'value': {'new': true},
    });
    expect(
      DashboardOverview.fromJson(
        raw,
        access(),
      ).metrics.containsKey('FUTURE_METRIC'),
      isFalse,
    );
    (raw['metrics'] as List).add((raw['metrics'] as List).first);
    expect(
      () => DashboardOverview.fromJson(raw, access()),
      throwsA(AppFailure.malformed),
    );
  });
  test(
    'calendar dates reject normalization and timezone-bearing timestamps',
    () {
      for (final invalid in [
        '2026-02-30',
        '2026-10-08T00:00:00Z',
        '1899-12-31',
        '2026-1-01',
      ]) {
        expect(() => BusinessDay.parse(invalid), throwsA(AppFailure.malformed));
      }
    },
  );
  test('periods match frozen Sunday week and full-month semantics', () {
    final today = BusinessDay.parse(businessDate);
    expect(DashboardPeriod.today.range(today).from.iso, businessDate);
    expect(DashboardPeriod.thisWeek.range(today).from.iso, '2026-10-04');
    expect(DashboardPeriod.thisWeek.range(today).to.iso, businessDate);
    expect(DashboardPeriod.thisMonth.range(today).from.iso, '2026-10-01');
    expect(DashboardPeriod.thisMonth.range(today).to.iso, '2026-10-31');
    expect(
      DashboardPeriod.thisMonth.range(BusinessDay.parse('2024-02-10')).to.iso,
      '2024-02-29',
    );
    expect(
      DashboardPeriod.thisWeek.range(BusinessDay.parse('2026-10-04')).from.iso,
      '2026-10-04',
    );
  });
  test('empty pages valid; malformed page metadata rejected', () {
    expect(
      DashboardPage<LowStockProduct>.fromJson(
        fixturePage([]),
        LowStockProduct.fromJson,
      ).items,
      isEmpty,
    );
    expect(
      () => DashboardPage<LowStockProduct>.fromJson({
        ...fixturePage([]),
        'size': 0,
      }, LowStockProduct.fromJson),
      throwsA(AppFailure.malformed),
    );
  });
  test(
    'repository uses bounded GETs and anchors ranges to server date',
    () async {
      final fixture = await dashboardAuth();
      addTearDown(fixture.auth.dispose);
      final repository = DashboardRepository(fixture.auth.repository.client);
      final result = await repository.load(access(), DashboardPeriod.thisWeek);
      expect(result.range.from.iso, '2026-10-04');
      expect(result.daily.date.iso, businessDate);
      expect(result.sales!.netProfit, '555.987');
      expect(result.lowStock!.items.single.quantity, '0.125');
      final calls = fixture.transport.calls
          .where((c) => c.path.startsWith('manager/'))
          .toList();
      expect(calls.length, 9);
      expect(calls.every((c) => c.method == 'GET' && c.token != null), isTrue);
      expect(
        calls.any(
          (c) =>
              c.path == 'manager/sales/summary?from=2026-10-04&to=2026-10-08',
        ),
        isTrue,
      );
      expect(
        calls.any((c) => c.path == 'manager/daily-summary?date=2026-10-08'),
        isTrue,
      );
    },
  );
  test(
    'today reuses daily sections instead of duplicate financial requests',
    () async {
      final fixture = await dashboardAuth();
      addTearDown(fixture.auth.dispose);
      final result = await DashboardRepository(fixture.auth.repository.client)
          .load(access(), DashboardPeriod.today);
      expect(identical(result.sales, result.daily.sales), isTrue);
      expect(
        fixture.transport.calls
            .where((c) => c.path.startsWith('manager/'))
            .length,
        6,
      );
    },
  );
  test('restricted permissions never request unauthorized endpoints', () async {
    final fixture = await dashboardAuth(
      permissions: ['DASHBOARD', 'SALES_VIEW'],
    );
    addTearDown(fixture.auth.dispose);
    final result = await DashboardRepository(fixture.auth.repository.client)
        .load(access(['DASHBOARD', 'SALES_VIEW']), DashboardPeriod.today);
    expect(result.sales, isNull);
    expect(result.slow, isNull);
    expect(result.top, isNull);
    expect(
      fixture.transport.calls
          .where((c) => c.path.startsWith('manager/'))
          .map((c) => Uri.parse(c.path).path),
      ['manager/dashboard', 'manager/daily-summary'],
    );
  });
  test('repository propagates safe HTTP failure without retries', () async {
    final fixture = await dashboardAuth();
    addTearDown(fixture.auth.dispose);
    fixture.transport.handler = (_, _, _, _) async =>
        throw AppFailure.http(503, {'message': 'private server details'});
    await expectLater(
      DashboardRepository(fixture.auth.repository.client)
          .load(access(), DashboardPeriod.today),
      throwsA(
        isA<AppFailure>().having(
          (f) => f.message,
          'safe message',
          isNot(contains('private')),
        ),
      ),
    );
    expect(
      fixture.transport.calls
          .where((c) => c.path.startsWith('manager/'))
          .length,
      1,
    );
  });
  test('mismatched response ranges fail closed', () async {
    final fixture = await dashboardAuth();
    addTearDown(fixture.auth.dispose);
    fixture.transport.handler = (_, path, _, _) async =>
        path.startsWith('manager/daily-summary')
        ? {...fixtureDaily(), 'date': '2026-10-09'}
        : dashboardResponse(path);
    await expectLater(
      DashboardRepository(fixture.auth.repository.client)
          .load(access(), DashboardPeriod.today),
      throwsA(AppFailure.malformed),
    );
  });
  test('missing zero-filled trend buckets are malformed', () async {
    final fixture = await dashboardAuth();
    addTearDown(fixture.auth.dispose);
    fixture.transport.handler = (_, path, _, _) async =>
        path.startsWith('manager/sales/trend')
        ? {'range': fixtureRange(), 'grouping': 'daily', 'buckets': []}
        : dashboardResponse(path);
    await expectLater(
      DashboardRepository(fixture.auth.repository.client)
          .load(access(), DashboardPeriod.today),
      throwsA(AppFailure.malformed),
    );
  });
}
