import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:manager_app/core/errors/app_failure.dart';
import 'package:manager_app/features/auth/state/auth_controller.dart';
import 'package:manager_app/features/sales/data/sales_models.dart';
import 'package:manager_app/features/sales/data/sales_repository.dart';
import 'package:manager_app/features/sales/state/sales_controller.dart';

import 'support/fakes.dart';
import 'support/inventory_fixtures.dart';
import 'support/sales_fixtures.dart';

void main() {
  Future<
    ({
      InvoiceListController list,
      InvoiceDetailController detail,
      SalesOverviewController overview,
      AuthController auth,
      FakeTransport transport,
    })
  >
  setup({
    List<String> permissions = salesPermissions,
    bool restricted = false,
  }) async {
    final f = await salesAuth(permissions: permissions, restricted: restricted);
    final repo = SalesRepository(f.auth.repository.client),
        list = InvoiceListController(
          f.auth,
          SalesRepository(f.auth.repository.client),
          size: 1,
        ),
        detail = InvoiceDetailController(
          f.auth,
          SalesRepository(f.auth.repository.client),
          1,
          size: 1,
        ),
        overview = SalesOverviewController(
          f.auth,
          SalesRepository(f.auth.repository.client),
        );
    addTearDown(() {
      list.dispose();
      detail.dispose();
      overview.dispose();
      f.auth.dispose();
    });
    expect(repo.client, same(f.auth.repository.client));
    return (
      list: list,
      detail: detail,
      overview: overview,
      auth: f.auth,
      transport: f.transport,
    );
  }

  test('invoice initial load/refresh singleflight retains safe rows', () async {
    final f = await setup(), gate = Completer<Object?>();
    f.transport.handler = (_, _, _, _) => gate.future;
    final pending = f.list.load();
    expect(f.list.loading, isTrue);
    expect(f.list.items, isEmpty);
    expect(f.list.load(), same(pending));
    gate.complete(inventoryPage([invoiceJson(1)], size: 1, total: 3));
    await pending;
    expect(f.list.page, 0);
    expect(f.list.hasMore, isTrue);
    f.transport.handler = (_, _, _, _) async =>
        throw AppFailure.http(503, null);
    await f.list.load();
    expect(f.list.items.single.id, 1);
    expect(f.list.error!.status, 503);
    expect(f.list.canLoadMore, isFalse);
  });
  test(
    'older search cannot overwrite newer and clearing restores normal query',
    () async {
      final f = await setup(), old = Completer<Object?>();
      f.transport.handler = (_, p, _, _) =>
          Uri.parse(p).queryParameters['q'] == 'old'
          ? old.future
          : Future.value(inventoryPage([invoiceJson(2)], size: 1));
      f.list.setSearch('old', immediate: true);
      final pending = f.list.load();
      f.list.setSearch('new', immediate: true);
      await f.list.load();
      old.complete(inventoryPage([invoiceJson(1)], size: 1));
      await pending;
      expect(f.list.items.single.id, 2);
      expect(f.list.loadedContext, contains('new'));
      f.list.setSearch('');
      await f.list.load();
      expect(f.list.page, 0);
      expect(
        Uri.parse(f.transport.calls.last.path).queryParameters.containsKey('q'),
        isFalse,
      );
    },
  );
  test('debounce suppresses transient input; dispose cancels timer', () async {
    final f = await setup();
    f.transport.calls.clear();
    f.list.setSearch(' x ');
    f.list.setSearch(' xy ');
    f.list.setSearch(' xyz ');
    expect(f.list.searching, isTrue);
    expect(f.transport.calls, isEmpty);
    await Future<void>.delayed(const Duration(milliseconds: 380));
    expect(f.transport.calls.length, 1);
    expect(
      Uri.parse(f.transport.calls.single.path).queryParameters['q'],
      'xyz',
    );
    f.list.setSearch(' xyz ');
    expect(f.transport.calls.length, 1);
    final temp = InvoiceListController(
      f.auth,
      SalesRepository(f.auth.repository.client),
    );
    temp.setSearch('cancelled');
    temp.dispose();
    await Future<void>.delayed(const Duration(milliseconds: 380));
    expect(f.transport.calls.length, 1);
  });
  test(
    'all supported filters restart at page zero and clear independently',
    () async {
      final f = await setup();
      await f.list.load();
      await f.list.nextPage();
      expect(f.list.page, 1);
      f.list.setStatus(InvoiceStatus.posted);
      await f.list.load();
      f.list.setPayment(InvoicePayment.credit);
      await f.list.load();
      f.list.setSort(InvoiceSort.lowest);
      await f.list.load();
      f.list.setCustomer(
        InvoiceCustomer.fromJson({
          'id': 1,
          'code': 'C1',
          'name': 'عميل',
          'phone': null,
        }),
      );
      await f.list.load();
      f.list.setRange(SalesRange.custom('2026-10-01', '2026-10-02'));
      await f.list.load();
      expect(Uri.parse(f.transport.calls.last.path).queryParameters, {
        'period': 'custom',
        'from': '2026-10-01',
        'to': '2026-10-02',
        'page': '0',
        'size': '1',
        'sort': 'total,asc',
        'status': 'POSTED',
        'paymentMethod': 'CREDIT',
        'customerId': '1',
      });
      f.list.setCustomer(null);
      await f.list.load();
      f.list.setPayment(null);
      await f.list.load();
      f.list.setStatus(null);
      await f.list.load();
      expect(
        Uri.parse(f.transport.calls.last.path).queryParameters.keys,
        isNot(contains('status')),
      );
      expect(f.list.page, 0);
    },
  );
  test(
    'next page singleflight retries same page after failure and stops at end',
    () async {
      final f = await setup();
      await f.list.load();
      f.transport.handler = (_, _, _, _) async =>
          throw AppFailure.http(503, null);
      await f.list.nextPage();
      expect(f.list.page, 0);
      expect(f.list.items.length, 1);
      expect(f.list.nextError, isNotNull);
      final gate = Completer<Object?>();
      f.transport.handler = (_, _, _, _) => gate.future;
      final next = f.list.nextPage();
      expect(f.list.nextPage(), same(next));
      expect(
        Uri.parse(f.transport.calls.last.path).queryParameters['page'],
        '1',
      );
      gate.complete(
        inventoryPage([invoiceJson(2)], page: 1, size: 1, total: 3),
      );
      await next;
      f.transport.handler = (_, p, _, _) async => salesResponse(p);
      await f.list.nextPage();
      expect(f.list.items.map((i) => i.id), [1, 2, 3]);
      expect(f.list.hasMore, isFalse);
      final count = f.transport.calls.length;
      await f.list.nextPage();
      expect(f.transport.calls.length, count);
    },
  );
  test('page overlap deduplicates IDs and empty page stops changing dataset traversal', () async {
    final f = await setup();
    await f.list.load();
    f.transport.handler = (_, _, _, _) async =>
        inventoryPage([invoiceJson(1)], page: 1, size: 1, total: 4);
    await f.list.nextPage();
    expect(f.list.items.length, 1);
    expect(f.list.page, 1);
    f.transport.handler = (_, _, _, _) async =>
        inventoryPage([], page: 2, size: 1, total: 4);
    await f.list.nextPage();
    expect(f.list.hasMore, isFalse);
  });
  test('refresh invalidates stale list next page', () async {
    final f = await setup();
    await f.list.load();
    final old = Completer<Object?>();
    f.transport.handler = (_, _, _, _) => old.future;
    final pending = f.list.nextPage();
    f.transport.handler = (_, p, _, _) async => salesResponse(p);
    await f.list.load();
    old.complete(inventoryPage([invoiceJson(2)], page: 1, size: 1, total: 3));
    await pending;
    expect(f.list.page, 0);
    expect(f.list.items.map((i) => i.id), [1]);
  });
  test(
    'detail singleflight and invoice identity invalidate late old response',
    () async {
      final f = await setup(), old = Completer<Object?>();
      f.transport.handler = (_, p, _, _) => Uri.parse(p).path.endsWith('/1')
          ? old.future
          : Future.value(detailInvoiceJson(id: 2, size: 1, returnsSize: 1));
      final pending = f.detail.load();
      expect(f.detail.load(), same(pending));
      await f.detail.selectInvoice(2);
      old.complete(detailInvoiceJson(size: 1, returnsSize: 1));
      await pending;
      expect(f.detail.invoice!.id, 2);
      expect(f.detail.invoice!.grossProfit, isNull);
    },
  );
  test('lines and return pages are independent simultaneous singleflight operations', () async {
    final f = await setup();
    await f.detail.load();
    final lines = Completer<Object?>(), returns = Completer<Object?>();
    f.transport.handler = (_, p, _, _) =>
        Uri.parse(p).queryParameters['page'] == '1'
        ? lines.future
        : returns.future;
    final a = f.detail.nextLines(), b = f.detail.nextReturns();
    expect(f.detail.nextLines(), same(a));
    expect(f.detail.nextReturns(), same(b));
    returns.complete(
      detailInvoiceJson(size: 1, returnsSize: 1, returnsPage: 1),
    );
    await b;
    expect(f.detail.lines.items.map((i) => i.id), [1]);
    expect(f.detail.returns.items.map((i) => i.id), [1, 2]);
    lines.complete(detailInvoiceJson(size: 1, returnsSize: 1, page: 1));
    await a;
    expect(f.detail.lines.items.map((i) => i.id), [1, 2]);
    expect(f.detail.returns.items.map((i) => i.id), [1, 2]);
    expect(f.detail.invoice!.returnedAmount, '9.000');
  });
  test(
    'nested failure retries only failed collection without resetting the other',
    () async {
      final f = await setup();
      await f.detail.load();
      await f.detail.nextReturns();
      f.transport.handler = (_, _, _, _) async =>
          throw AppFailure.http(503, null);
      await f.detail.nextLines();
      expect(f.detail.lines.page, 0);
      expect(f.detail.lines.error, isNotNull);
      expect(f.detail.returns.page, 1);
      expect(f.detail.invoice, isNotNull);
      f.transport.handler = (_, p, _, _) async => salesResponse(p);
      await f.detail.nextLines();
      expect(f.detail.lines.page, 1);
      expect(f.detail.returns.page, 1);
      expect(
        Uri.parse(f.transport.calls.last.path).queryParameters['page'],
        '1',
      );
    },
  );
  test(
    'refresh resets both nested pages and invalidates both late appends',
    () async {
      final f = await setup();
      await f.detail.load();
      final old = Completer<Object?>();
      f.transport.handler = (_, _, _, _) => old.future;
      final a = f.detail.nextLines(), b = f.detail.nextReturns();
      f.transport.handler = (_, p, _, _) async => salesResponse(p);
      await f.detail.load();
      old.complete(null);
      await Future.wait([a, b]);
      expect(f.detail.lines.page, 0);
      expect(f.detail.returns.page, 0);
      expect(f.detail.lines.items.length, 1);
      expect(f.detail.returns.items.length, 1);
    },
  );
  for (final code in [403, 404]) {
    test(
      'detail $code clears all finances and blocks late parallel page while me is slow',
      () async {
        final f = await setup();
        await f.detail.load();
        final old = Completer<Object?>(), me = Completer<Object?>();
        f.transport.handler = (_, p, _, _) => p == 'auth/me'
            ? me.future
            : Uri.parse(p).queryParameters['page'] == '1'
            ? Future.error(AppFailure.http(code, null))
            : old.future;
        final a = f.detail.nextLines(), b = f.detail.nextReturns();
        await Future<void>.delayed(Duration.zero);
        expect(f.detail.invoice, isNull);
        expect(f.detail.lines.items, isEmpty);
        expect(f.detail.returns.items, isEmpty);
        expect(f.detail.error!.status, code);
        old.complete(
          detailInvoiceJson(size: 1, returnsSize: 1, returnsPage: 1),
        );
        await b;
        expect(f.detail.returns.items, isEmpty);
        me.complete(userJson(permissions: salesPermissions));
        await a;
      },
    );
  }
  test(
    'detail transient refresh failure retains safe previous snapshot',
    () async {
      final f = await setup();
      await f.detail.load();
      await f.detail.nextReturns();
      f.transport.handler = (_, _, _, _) async =>
          throw AppFailure.http(503, null);
      await f.detail.load();
      expect(f.detail.invoice, isNotNull);
      expect(f.detail.returns.page, 1);
      expect(f.detail.error!.status, 503);
    },
  );
  test(
    'overview retains previous range on failure; new date wins late response',
    () async {
      final f = await setup();
      await f.overview.load();
      f.transport.handler = (_, _, _, _) async =>
          throw AppFailure.http(503, null);
      f.overview.setRange(const SalesRange.preset(SalesPeriod.today));
      await f.overview.load();
      expect(f.overview.data!.summary.range.to.iso, '2026-10-31');
      expect(f.overview.error, isNotNull);
      final old = Completer<Object?>();
      f.transport.handler = (_, p, _, _) =>
          p == 'manager/sales/summary?period=this_week'
          ? old.future
          : Future.value(salesResponse(p));
      f.overview.setRange(const SalesRange.preset(SalesPeriod.week));
      final pending = f.overview.load();
      f.overview.setRange(const SalesRange.preset(SalesPeriod.today));
      await f.overview.load();
      old.complete(salesResponse('manager/sales/summary?period=this_week'));
      await pending;
      expect(f.overview.data!.summary.range.to.iso, '2026-10-08');
    },
  );
  test('overview forbidden parallel endpoint clears before slow other result and me', () async {
    final f = await setup();
    await f.overview.load();
    final me = Completer<Object?>(), slow = Completer<Object?>();
    f.transport.handler = (_, p, _, _) => p == 'auth/me'
        ? me.future
        : p.startsWith('manager/sales/trend')
        ? Future.error(AppFailure.http(403, null))
        : p.startsWith('manager/sales/top-products')
        ? slow.future
        : Future.value(salesResponse(p));
    final pending = f.overview.load();
    await Future<void>.delayed(Duration.zero);
    expect(f.overview.data, isNull);
    expect(f.overview.error!.status, 403);
    me.complete(userJson(permissions: salesPermissions));
    await pending;
    slow.complete(null);
    expect(f.overview.data, isNull);
  });
  test('cost/profit downgrade clears all loaded models immediately before slow reload', () async {
    final f = await setup();
    await Future.wait([f.list.load(), f.detail.load(), f.overview.load()]);
    expect(f.detail.invoice!.historicalCost, isNotNull);
    expect(f.overview.data!.summary.netProfit, isNotNull);
    final permissions = salesPermissions
            .where(
              (p) => ![
                'SALES_COST_VIEW',
                'SALES_PROFIT_VIEW',
                'REPORTS_PROFIT',
              ].contains(p),
            )
            .toList(),
        gate = Completer<Object?>();
    f.transport.handler = (_, p, _, _) => p == 'auth/me'
        ? Future.value(userJson(permissions: permissions))
        : gate.future;
    await f.auth.revalidate();
    expect(f.list.items, isEmpty);
    expect(f.detail.invoice, isNull);
    expect(f.overview.data, isNull);
    f.transport.handler = (_, p, _, _) async => salesResponse(p);
    f.list.setSearch('reload', immediate: true);
    await f.list.load();
    await f.detail.selectInvoice(2);
    f.overview.setRange(const SalesRange.preset(SalesPeriod.today));
    await f.overview.load();
    expect(f.list.items.first.historicalCost, isNull);
    expect(f.list.items.first.grossProfit, isNull);
    expect(f.detail.lines.items.first.historicalUnitCost, isNull);
    expect(f.overview.data!.summary.netProfit, isNull);
    expect(f.overview.data!.top.first.profit, isNull);
    gate.complete(null);
  });
  for (final restriction in ['none', 'password', 'logout']) {
    test('$restriction invalidates every model and late responses', () async {
      final f = await setup();
      await Future.wait([f.list.load(), f.detail.load(), f.overview.load()]);
      final old = Completer<Object?>();
      f.transport.handler = (_, p, _, _) => p == 'auth/me'
          ? Future.value(
              userJson(
                permissions: restriction == 'none' ? [] : salesPermissions,
                restricted: restriction == 'password',
              ),
            )
          : old.future;
      final pending = f.detail.nextLines();
      if (restriction == 'logout') {
        await f.auth.terminate();
      } else {
        await f.auth.revalidate();
      }
      expect(f.list.items, isEmpty);
      expect(f.detail.invoice, isNull);
      expect(f.overview.data, isNull);
      old.complete(detailInvoiceJson(page: 1, size: 1, returnsSize: 1));
      await pending;
      expect(f.detail.invoice, isNull);
      expect(f.detail.lines.items, isEmpty);
    });
  }
  for (final restricted in [true, false]) {
    test(
      'initial forbidden/restricted=$restricted sends no business requests',
      () async {
        final f = await setup(
          permissions: restricted ? salesPermissions : [],
          restricted: restricted,
        );
        f.transport.calls.clear();
        await Future.wait([f.list.load(), f.detail.load(), f.overview.load()]);
        expect(f.transport.calls, isEmpty);
        expect(f.detail.invoice, isNull);
      },
    );
  }
  test('401 retries once using released central refresh; terminal failure clears all', () async {
    final f = await setup();
    var attempts = 0;
    f.transport.handler = (_, p, _, _) async {
      if (p.startsWith('manager/invoices') && attempts++ == 0) {
        throw AppFailure.signedOut;
      }
      if (p == 'auth/refresh') {
        return {
          ...loginJson(access: 'rotated-fixture'),
          'user': userJson(permissions: salesPermissions),
        };
      }
      return salesResponse(p);
    };
    await f.list.load();
    expect(f.list.items, isNotEmpty);
    expect(attempts, 2);
    expect(f.transport.calls.where((c) => c.path == 'auth/refresh').length, 1);
    await f.detail.load();
    await f.overview.load();
    f.transport.calls.clear();
    f.transport.handler = (_, _, _, _) async => throw AppFailure.signedOut;
    await f.list.load();
    expect(f.auth.status, AuthStatus.signedOut);
    expect(f.list.items, isEmpty);
    expect(f.detail.invoice, isNull);
    expect(f.overview.data, isNull);
    expect(f.transport.calls.where((c) => c.path == 'auth/refresh').length, 1);
  });
}
