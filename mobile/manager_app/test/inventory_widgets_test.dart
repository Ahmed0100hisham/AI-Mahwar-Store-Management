import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:manager_app/app/manager_app.dart';
import 'package:manager_app/core/errors/app_failure.dart';
import 'package:manager_app/core/theme/app_theme.dart';
import 'package:manager_app/features/auth/state/auth_controller.dart';
import 'package:manager_app/features/inventory/presentation/inventory_screen.dart';
import 'package:manager_app/features/inventory/presentation/product_detail_screen.dart';
import 'package:manager_app/features/inventory/presentation/movement_screen.dart';

import 'support/inventory_fixtures.dart';
import 'support/fakes.dart';

void main() {
  Future<({AuthController auth, FakeTransport transport})> show(
    WidgetTester tester, {
    List<String> permissions = inventoryPermissions,
    bool restricted = false,
    bool low = false,
    String screen = 'list',
    Handler? handler,
    bool shell = false,
  }) async {
    final f = await inventoryAuth(
      permissions: permissions,
      restricted: restricted,
    );
    addTearDown(f.auth.dispose);
    if (handler != null) f.transport.handler = handler;
    await tester.pumpWidget(
      shell
          ? ManagerApp(auth: f.auth, monitorSession: false)
          : MaterialApp(
              theme: managerTheme(Brightness.light),
              builder: (_, child) => Directionality(
                textDirection: TextDirection.rtl,
                child: child!,
              ),
              home: switch (screen) {
                'detail' => ProductDetailScreen(auth: f.auth, id: 1),
                'movements' => MovementScreen(
                  auth: f.auth,
                  productId: 1,
                  unit: 'قطعة',
                ),
                _ => Scaffold(
                  body: InventoryScreen(auth: f.auth, lowStock: low),
                ),
              },
            ),
    );
    return f;
  }

  Future<void> bottom(WidgetTester tester) async {
    await tester.drag(find.byType(ListView).first, const Offset(0, -1800));
    await tester.pumpAndSettle();
  }

  testWidgets(
    'Arabic RTL populated inventory exposes exact prices units and fractional quantities',
    (tester) async {
      await show(tester);
      await tester.pumpAndSettle();
      expect(
        Directionality.of(tester.element(find.text('المنتجات والمخزون'))),
        TextDirection.rtl,
      );
      expect(find.text('منتج 1'), findsOneWidget);
      expect(find.text('2.125 قطعة'), findsOneWidget);
      expect(find.text('1,250.500 د.ك'), findsWidgets);
      expect(find.text('777.987 د.ك'), findsWidgets);
      expect(find.text('مخزون منخفض'), findsWidgets);
    },
  );
  for (final entry in [
    ('list', false, 'لا توجد منتجات لعرضها'),
    ('list', true, 'لا توجد منتجات منخفضة المخزون'),
    ('movements', false, 'لا توجد حركات مخزون لهذا المنتج'),
  ]) {
    testWidgets('legitimate empty ${entry.$3}', (tester) async {
      await show(
        tester,
        screen: entry.$1,
        low: entry.$2,
        handler: (_, _, _, _) async => inventoryPage([]),
      );
      await tester.pumpAndSettle();
      expect(find.text(entry.$3), findsOneWidget);
      expect(find.text('إعادة المحاولة'), findsNothing);
    });
  }
  testWidgets(
    'barcode search is debounced and clearing restores normal server query',
    (tester) async {
      final f = await show(tester);
      await tester.pumpAndSettle();
      f.transport.calls.clear();
      await tester.enterText(find.byType(TextField), '001234567891');
      await tester.pump(const Duration(milliseconds: 200));
      expect(f.transport.calls, isEmpty);
      await tester.pump(const Duration(milliseconds: 200));
      await tester.pumpAndSettle();
      expect(
        Uri.parse(f.transport.calls.last.path).queryParameters['q'],
        '001234567891',
      );
      await tester.tap(find.byTooltip('مسح البحث'));
      await tester.pumpAndSettle();
      expect(
        Uri.parse(f.transport.calls.last.path).queryParameters.containsKey('q'),
        isFalse,
      );
    },
  );
  testWidgets('empty search is a distinct legitimate state', (tester) async {
    await show(
      tester,
      handler: (_, p, _, _) async =>
          Uri.parse(p).queryParameters.containsKey('q')
          ? inventoryPage([])
          : inventoryResponse(p),
    );
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), 'not found');
    await tester.pump(const Duration(milliseconds: 400));
    await tester.pumpAndSettle();
    expect(find.text('لا توجد نتائج مطابقة'), findsOneWidget);
  });
  testWidgets(
    'low-stock membership and zero quantity show textual stock status',
    (tester) async {
      await show(tester, low: true);
      await tester.pumpAndSettle();
      expect(find.text('منتج 1'), findsOneWidget);
      await bottom(tester);
      expect(find.text('نفد المخزون'), findsOneWidget);
      expect(find.text('0.000 قطعة'), findsOneWidget);
    },
  );
  testWidgets(
    'list row navigates to real detail then actual movements and back',
    (tester) async {
      final f = await show(tester);
      await tester.pumpAndSettle();
      await tester.tap(find.text('منتج 1'));
      await tester.pumpAndSettle();
      expect(find.text('تفاصيل المنتج'), findsOneWidget);
      expect(find.text('001234567891'), findsOneWidget);
      await tester.drag(
        find.byType(SingleChildScrollView),
        const Offset(0, -800),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('حركات المخزون'));
      await tester.pumpAndSettle();
      expect(find.text('تسوية صرف'), findsWidgets);
      expect(find.text('-2.125 قطعة'), findsWidgets);
      expect(
        f.transport.calls.any(
          (c) => c.path.startsWith('manager/products/1/movements'),
        ),
        isTrue,
      );
      await tester.pageBack();
      await tester.pumpAndSettle();
      expect(find.text('تفاصيل المنتج'), findsOneWidget);
    },
  );
  for (final screen in ['list', 'detail', 'movements']) {
    testWidgets(
      '$screen rejects over-returned cost in text and accessibility',
      (tester) async {
        final semantics = tester.ensureSemantics();
        await show(
          tester,
          screen: screen,
          permissions: inventoryPermissions
              .where((p) => p != 'PRODUCT_COST')
              .toList(),
        );
        await tester.pumpAndSettle();
        for (final value in ['777.987', '778.987']) {
          expect(find.textContaining(value), findsNothing);
          expect(
            find.bySemanticsLabel(RegExp(RegExp.escape(value))),
            findsNothing,
          );
        }
        expect(find.textContaining('تكلفة'), findsNothing);
        semantics.dispose();
      },
    );
  }
  testWidgets(
    'permission removal immediately removes stale cost before slow reload',
    (tester) async {
      final f = await show(tester, screen: 'detail');
      await tester.pumpAndSettle();
      expect(find.text('777.987 د.ك'), findsOneWidget);
      final gate = Completer<Object?>();
      f.transport.handler = (_, p, _, _) => p == 'auth/me'
          ? Future.value(
              userJson(
                permissions: inventoryPermissions
                    .where((p) => p != 'PRODUCT_COST')
                    .toList(),
              ),
            )
          : gate.future;
      await f.auth.revalidate();
      await tester.pump();
      expect(find.textContaining('777.987'), findsNothing);
      expect(find.byType(LinearProgressIndicator), findsOneWidget);
      gate.complete(detailJson());
      await tester.pumpAndSettle();
      expect(find.textContaining('777.987'), findsNothing);
    },
  );
  testWidgets(
    'initial progress has no fake stock and safe failure offers retry',
    (tester) async {
      final gate = Completer<Object?>();
      final f = await show(tester, handler: (_, _, _, _) => gate.future);
      await tester.pump();
      expect(find.byType(LinearProgressIndicator), findsOneWidget);
      expect(find.text('منتج 1'), findsNothing);
      gate.completeError(AppFailure.http(503, null));
      await tester.pumpAndSettle();
      expect(find.text('إعادة المحاولة'), findsOneWidget);
      f.transport.handler = (_, p, _, _) async => inventoryResponse(p);
      await tester.tap(find.text('إعادة المحاولة'));
      await tester.pumpAndSettle();
      expect(find.text('منتج 1'), findsOneWidget);
    },
  );
  testWidgets(
    'pagination progress and retry append without duplicating client rows',
    (tester) async {
      final gate = Completer<Object?>();
      var fail = true;
      final f = await show(
        tester,
        handler: (_, p, _, _) async {
          final q = Uri.parse(p).queryParameters;
          if (q['page'] == '1') {
            if (fail) return gate.future;
            return inventoryPage([productJson(2)], page: 1, total: 21);
          }
          return inventoryPage([productJson(1)], total: 21);
        },
      );
      await tester.pumpAndSettle();
      await bottom(tester);
      await tester.tap(find.text('تحميل المزيد'));
      await tester.pump();
      expect(find.byType(CircularProgressIndicator), findsOneWidget);
      gate.completeError(AppFailure.http(503, null));
      await tester.pumpAndSettle();
      expect(find.text('إعادة المحاولة'), findsOneWidget);
      fail = false;
      await bottom(tester);
      await tester.ensureVisible(find.text('إعادة المحاولة'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('إعادة المحاولة'));
      await tester.pumpAndSettle();
      expect(
        f.transport.calls
            .where((c) => Uri.parse(c.path).queryParameters['page'] == '1')
            .length,
        2,
      );
      expect(find.text('منتج 2'), findsOneWidget);
      await bottom(tester);
      expect(find.text('نهاية النتائج'), findsOneWidget);
    },
  );
  testWidgets(
    'custom movement range validates dates and sends only frozen parameters',
    (tester) async {
      final f = await show(tester, screen: 'movements');
      await tester.pumpAndSettle();
      await tester.tap(find.text('تحديد تاريخين'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('تطبيق'));
      await tester.pumpAndSettle();
      expect(find.textContaining('أدخل تاريخين'), findsOneWidget);
      await tester.enterText(find.byType(TextFormField).at(0), '2026-09-01');
      await tester.enterText(find.byType(TextFormField).at(1), '2026-09-30');
      await tester.tap(find.text('تطبيق'));
      await tester.pumpAndSettle();
      expect(
        Uri.parse(f.transport.calls.last.path).queryParameters['from'],
        '2026-09-01',
      );
    },
  );
  testWidgets(
    'unauthorized shell keeps profile and has no inventory destination',
    (tester) async {
      await show(tester, permissions: ['DASHBOARD'], shell: true);
      await tester.pumpAndSettle();
      await tester.tap(find.byIcon(Icons.menu));
      await tester.pumpAndSettle();
      expect(find.text('المنتجات والمخزون'), findsNothing);
      expect(find.text('الملف الشخصي والأجهزة'), findsOneWidget);
    },
  );
  for (final restricted in [false, true]) {
    testWidgets(
      'protected inventory blocked for signed-out/restricted=$restricted',
      (tester) async {
        final f = await show(tester, restricted: restricted);
        if (!restricted) await f.auth.terminate();
        await tester.pumpAndSettle();
        expect(find.textContaining('هذه الصفحة غير متاحة'), findsOneWidget);
        expect(find.textContaining('777.987'), findsNothing);
      },
    );
  }
  testWidgets('dashboard low-stock action opens the real inventory endpoint', (
    tester,
  ) async {
    final f = await show(tester, shell: true);
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(
      find.text('عرض المخزون المنخفض'),
      500,
      scrollable: find.byType(Scrollable).first,
    );
    await tester.tap(find.text('عرض المخزون المنخفض'));
    await tester.pumpAndSettle();
    expect(find.byType(InventoryScreen), findsOneWidget);
    expect(
      f.transport.calls.last.path,
      startsWith('manager/inventory/low-stock'),
    );
  });
  testWidgets('view-only products hide inactive filter and movement access', (
    tester,
  ) async {
    final f = await show(tester, permissions: ['PRODUCTS_VIEW']);
    await tester.pumpAndSettle();
    expect(find.byType(CheckboxListTile), findsNothing);
    await tester.tap(find.text('منتج 1'));
    await tester.pumpAndSettle();
    expect(find.text('تفاصيل المنتج'), findsOneWidget);
    expect(find.text('حركات المخزون'), findsNothing);
    expect(
      f.transport.calls.any((c) => c.path.contains('/movements')),
      isFalse,
    );
  });
  testWidgets(
    'inventory-only permission exposes low stock without protected detail links',
    (tester) async {
      final f = await show(
        tester,
        permissions: ['INVENTORY', 'REPORTS_VIEW', 'REPORTS_INVENTORY'],
      );
      await tester.pumpAndSettle();
      expect(find.text('منتج 1'), findsOneWidget);
      expect(find.text('عرض التفاصيل'), findsNothing);
      expect(
        f.transport.calls.last.path,
        startsWith('manager/inventory/low-stock'),
      );
    },
  );
  testWidgets(
    'authorized omitted purchase cost remains absent instead of substituted zero',
    (tester) async {
      await show(
        tester,
        screen: 'detail',
        handler: (_, _, _, _) async => detailJson()..remove('purchaseCost'),
      );
      await tester.pumpAndSettle();
      expect(find.text('تكلفة الشراء للوحدة'), findsNothing);
      expect(find.text('0.000 د.ك'), findsNothing);
    },
  );
  testWidgets(
    'reused movement widget clears old product rows while replacement loads',
    (tester) async {
      final f = await inventoryAuth();
      addTearDown(f.auth.dispose);
      Widget app(int id) => MaterialApp(
        home: MovementScreen(auth: f.auth, productId: id, unit: 'قطعة'),
      );
      await tester.pumpWidget(app(1));
      await tester.pumpAndSettle();
      expect(find.text('تسوية صرف'), findsWidgets);
      final gate = Completer<Object?>();
      f.transport.handler = (_, _, _, _) => gate.future;
      await tester.pumpWidget(app(2));
      await tester.pump();
      expect(find.text('تسوية صرف'), findsNothing);
      gate.complete(inventoryPage([]));
      await tester.pumpAndSettle();
      expect(find.text('لا توجد حركات مخزون لهذا المنتج'), findsOneWidget);
    },
  );
  for (final screen in ['list', 'detail', 'movements']) {
    testWidgets(
      '$screen narrow error and retry remain accessible at text scale 1.5',
      (tester) async {
        tester.view.physicalSize = const Size(320, 700);
        tester.view.devicePixelRatio = 1;
        tester.platformDispatcher.textScaleFactorTestValue = 1.5;
        addTearDown(() {
          tester.view.resetPhysicalSize();
          tester.view.resetDevicePixelRatio();
          tester.platformDispatcher.clearTextScaleFactorTestValue();
        });
        await show(
          tester,
          screen: screen,
          handler: (_, _, _, _) async => throw AppFailure.http(503, null),
        );
        await tester.pumpAndSettle();
        expect(find.text('إعادة المحاولة'), findsOneWidget);
        expect(tester.takeException(), isNull);
      },
    );
  }
  for (final size in [
    const Size(320, 700),
    const Size(390, 844),
    const Size(800, 1024),
  ]) {
    for (final screen in ['list', 'detail', 'movements']) {
      testWidgets(
        '$screen RTL ${size.width} at text scale 1.5 has no overflow including large exact decimal',
        (tester) async {
          tester.view.physicalSize = size;
          tester.view.devicePixelRatio = 1;
          tester.platformDispatcher.textScaleFactorTestValue = 1.5;
          addTearDown(() {
            tester.view.resetPhysicalSize();
            tester.view.resetDevicePixelRatio();
            tester.platformDispatcher.clearTextScaleFactorTestValue();
          });
          await show(
            tester,
            screen: screen,
            handler: (_, p, _, _) async => screen == 'detail'
                ? {
                    ...detailJson(),
                    'quantity': '9007199254740993.125',
                    'nameAr': productJson(3)['nameAr'],
                  }
                : inventoryResponse(p),
          );
          await tester.pumpAndSettle();
          expect(tester.takeException(), isNull);
          if (screen == 'list') {
            await bottom(tester);
            expect(find.text('9,007,199,254,740,993.125 قطعة'), findsOneWidget);
          } else if (screen == 'detail') {
            expect(find.text('9,007,199,254,740,993.125 قطعة'), findsOneWidget);
          }
          expect(tester.takeException(), isNull);
        },
      );
    }
  }
}
