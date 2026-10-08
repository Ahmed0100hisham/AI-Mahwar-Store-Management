import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:manager_app/app/manager_app.dart';
import 'package:manager_app/core/errors/app_failure.dart';
import 'package:manager_app/features/auth/state/auth_controller.dart';
import 'package:manager_app/features/dashboard/presentation/dashboard_screen.dart';
import 'package:manager_app/features/dashboard/data/dashboard_models.dart';

import 'support/dashboard_fixtures.dart';
import 'support/fakes.dart';

void main() {
  Future<({AuthController auth, FakeTransport transport})> show(
    WidgetTester tester, {
    List<String> permissions = dashboardPermissions,
    bool restricted = false,
    Handler? handler,
  }) async {
    final fixture = await dashboardAuth(
      permissions: permissions,
      restricted: restricted,
    );
    if (handler != null) fixture.transport.handler = handler;
    addTearDown(fixture.auth.dispose);
    await tester.pumpWidget(
      ManagerApp(auth: fixture.auth, monitorSession: false),
    );
    return fixture;
  }

  testWidgets('authenticated dashboard is Arabic RTL with exact large money', (
    tester,
  ) async {
    await show(tester);
    await tester.pumpAndSettle();
    expect(find.byType(DashboardScreen), findsOneWidget);
    expect(
      Directionality.of(tester.element(find.text('لوحة المتابعة'))),
      TextDirection.rtl,
    );
    expect(find.text('9,007,199,254,740,993.125 د.ك'), findsOneWidget);
    expect(find.text('555.987 د.ك'), findsOneWidget);
    expect(find.text('999.987 د.ك'), findsOneWidget);
    expect(find.text('888.987 د.ك'), findsOneWidget);
    expect(find.text('444.987 د.ك'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
  testWidgets('initial loading has no business numbers', (tester) async {
    final gate = Completer<Object?>();
    await show(
      tester,
      handler: (_, path, _, _) => path == 'manager/dashboard'
          ? gate.future
          : Future.value(dashboardResponse(path)),
    );
    await tester.pump();
    expect(find.text('جارٍ تحميل لوحة المتابعة…'), findsOneWidget);
    expect(find.textContaining('د.ك'), findsNothing);
    gate.complete(fixtureOverview());
    await tester.pumpAndSettle();
  });
  testWidgets(
    'profit cost and balances cannot leak through text or semantics',
    (tester) async {
      final semantics = tester.ensureSemantics();
      await show(
        tester,
        permissions: dashboardPermissions
            .where(
              (p) => ![
                'REPORTS_PROFIT',
                'PRODUCT_COST',
                'CUSTOMER_BALANCE_VIEW',
                'SUPPLIER_BALANCE_VIEW',
              ].contains(p),
            )
            .toList(),
      );
      await tester.pumpAndSettle();
      for (final sentinel in [
        '111.987',
        '222.987',
        '333.987',
        '444.987',
        '445.987',
        '555.987',
        '888.987',
        '999.987',
      ]) {
        expect(find.textContaining(sentinel), findsNothing);
        expect(
          find.bySemanticsLabel(RegExp(RegExp.escape(sentinel))),
          findsNothing,
        );
      }
      expect(find.textContaining('صافي الربح'), findsNothing);
      expect(find.textContaining('قيمة المخزون بالتكلفة'), findsNothing);
      expect(find.text('ديون العملاء الحالية'), findsNothing);
      expect(find.text('مستحقات الموردين الحالية'), findsNothing);
      semantics.dispose();
    },
  );
  testWidgets(
    'authorized omitted profit and valuation never become fake zero',
    (tester) async {
      await show(
        tester,
        handler: (_, path, _, _) async {
          final response = dashboardResponse(path);
          if (path == 'manager/dashboard') {
            final j = response as Map<String, Object?>;
            (j['metrics'] as List).removeWhere(
              (raw) => (raw as Map)['name'] == 'NET_PROFIT',
            );
          }
          if (path.startsWith('manager/daily-summary')) {
            final j = response as Map<String, Object?>;
            (j['sales'] as Map).remove('profit');
            ((j['inventorySnapshot'] as Map)['inventory'] as Map).remove(
              'inventoryValue',
            );
          }
          return response;
        },
      );
      await tester.pumpAndSettle();
      expect(find.textContaining('صافي الربح'), findsNothing);
      expect(find.textContaining('قيمة المخزون بالتكلفة'), findsNothing);
      expect(find.textContaining('صافي ربح الشهر'), findsNothing);
    },
  );
  testWidgets('empty business lists have useful messages and legitimate zero', (
    tester,
  ) async {
    await show(
      tester,
      handler: (_, path, _, _) async {
        final uri = Uri.parse(path);
        final response = dashboardResponse(path);
        if (uri.path == 'manager/sales/top-products') {
          (response as Map)['items'] = <Object?>[];
        }
        if (uri.path == 'manager/inventory/low-stock' ||
            uri.path == 'manager/sales/slow-products') {
          return fixturePage([]);
        }
        if (uri.path == 'manager/daily-summary') {
          final sales = (response as Map)['sales'] as Map;
          sales['netSales'] = '0.000';
          sales['invoiceCount'] = 0;
        }
        return response;
      },
    );
    await tester.pumpAndSettle();
    expect(find.text('0.000 د.ك'), findsWidgets);
    expect(find.text('لا توجد مبيعات أصناف خلال هذه الفترة.'), findsOneWidget);
    expect(find.text('لا توجد أصناف عند حد النقص حالياً.'), findsOneWidget);
    expect(find.text('لا توجد أصناف تطابق معيار بطء الحركة.'), findsOneWidget);
  });
  testWidgets('initial safe error retries without server detail leakage', (
    tester,
  ) async {
    var fail = true;
    await show(
      tester,
      handler: (_, path, _, _) async {
        if (fail) {
          throw AppFailure.http(503, {'message': 'private SQL stack trace'});
        }
        return dashboardResponse(path);
      },
    );
    await tester.pumpAndSettle();
    expect(find.text('إعادة المحاولة'), findsOneWidget);
    expect(find.textContaining('SQL'), findsNothing);
    fail = false;
    await tester.tap(find.text('إعادة المحاولة'));
    await tester.pumpAndSettle();
    expect(find.text('9,007,199,254,740,993.125 د.ك'), findsOneWidget);
  });
  testWidgets('refresh failure retains prior content with stale-data notice', (
    tester,
  ) async {
    final fixture = await show(tester);
    await tester.pumpAndSettle();
    fixture.transport.handler = (_, _, _, _) async =>
        throw AppFailure.http(503, null);
    await tester.tap(find.text('تحديث البيانات'));
    await tester.pumpAndSettle();
    expect(find.text('9,007,199,254,740,993.125 د.ك'), findsOneWidget);
    expect(
      find.text('تعذر التحديث. البيانات المعروضة من آخر تحميل ناجح.'),
      findsOneWidget,
    );
  });
  testWidgets('pull to refresh uses one authenticated dashboard batch', (
    tester,
  ) async {
    final fixture = await show(tester);
    await tester.pumpAndSettle();
    final before = fixture.transport.calls
        .where((c) => c.path == 'manager/dashboard')
        .length;
    await tester.drag(
      find.byType(SingleChildScrollView).first,
      const Offset(0, 400),
    );
    await tester.pumpAndSettle();
    expect(
      fixture.transport.calls
          .where((c) => c.path == 'manager/dashboard')
          .length,
      before + 1,
    );
    expect(tester.takeException(), isNull);
  });
  testWidgets('period change uses full month and preserves daily context', (
    tester,
  ) async {
    final fixture = await show(tester);
    await tester.pumpAndSettle();
    await tester.tap(find.byType(DropdownButtonFormField<DashboardPeriod>));
    await tester.pumpAndSettle();
    await tester.tap(find.text('هذا الشهر').last);
    await tester.pumpAndSettle();
    expect(
      fixture.transport.calls.any(
        (c) => c.path == 'manager/sales/summary?from=2026-10-01&to=2026-10-31',
      ),
      isTrue,
    );
    expect(find.text('صافي المبيعات • هذا الشهر'), findsOneWidget);
    expect(find.textContaining('يظل يومياً عند تغيير الفترة'), findsOneWidget);
  });
  for (final size in [
    const Size(320, 700),
    const Size(390, 844),
    const Size(800, 1024),
  ]) {
    testWidgets('responsive Arabic dashboard without overflow at $size', (
      tester,
    ) async {
      tester.view.physicalSize = size;
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      tester.platformDispatcher.textScaleFactorTestValue = 1.5;
      addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
      await show(tester);
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(find.text('تحديث البيانات'), findsOneWidget);
      await tester.drag(
        find.byType(SingleChildScrollView).first,
        const Offset(0, -400),
      );
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    });
  }
  testWidgets('restricted user cannot enter dashboard or bypass with back', (
    tester,
  ) async {
    final fixture = await show(tester, restricted: true);
    await tester.pumpAndSettle();
    expect(find.byType(DashboardScreen), findsNothing);
    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(find.byType(DashboardScreen), findsNothing);
    expect(
      fixture.transport.calls.where((c) => c.path.startsWith('manager/')),
      isEmpty,
    );
  });
  testWidgets('terminal dashboard 401 routes back to existing login', (
    tester,
  ) async {
    await show(
      tester,
      handler: (_, _, _, _) async => throw AppFailure.signedOut,
    );
    await tester.pumpAndSettle();
    expect(find.text('مرحباً بك'), findsOneWidget);
    expect(find.byType(DashboardScreen), findsNothing);
  });
}
