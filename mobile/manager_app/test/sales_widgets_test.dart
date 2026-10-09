import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:manager_app/app/manager_app.dart';
import 'package:manager_app/core/errors/app_failure.dart';
import 'package:manager_app/core/theme/app_theme.dart';
import 'package:manager_app/features/auth/state/auth_controller.dart';
import 'package:manager_app/features/sales/data/sales_models.dart';
import 'package:manager_app/features/sales/presentation/sales_screen.dart';
import 'package:manager_app/features/sales/presentation/invoice_detail_screen.dart';
import 'package:manager_app/features/sales/presentation/invoice_list.dart';

import 'support/fakes.dart';
import 'support/inventory_fixtures.dart';
import 'support/sales_fixtures.dart';

void main() {
  Future<({AuthController auth, FakeTransport transport})> show(
    WidgetTester tester, {
    String screen = 'overview',
    List<String> permissions = salesPermissions,
    Handler? handler,
    bool shell = false,
    Brightness brightness = Brightness.light,
  }) async {
    final f = await salesAuth(permissions: permissions);
    addTearDown(f.auth.dispose);
    if (handler != null) f.transport.handler = handler;
    await tester.pumpWidget(
      shell
          ? ManagerApp(auth: f.auth, monitorSession: false)
          : MaterialApp(
              theme: managerTheme(brightness),
              builder: (_, child) => Directionality(
                textDirection: TextDirection.rtl,
                child: child!,
              ),
              home: screen == 'detail'
                  ? InvoiceDetailScreen(auth: f.auth, id: 1)
                  : Scaffold(
                      body: SalesScreen(
                        auth: f.auth,
                        invoices: screen == 'list',
                      ),
                    ),
            ),
    );
    return f;
  }

  Future<void> reveal(WidgetTester tester, Finder finder) async {
    await tester.scrollUntilVisible(
      finder,
      250,
      scrollable: find.byType(Scrollable).first,
      maxScrolls: 100,
    );
    await tester.pumpAndSettle();
  }

  Future<void> bottom(WidgetTester tester) async {
    for (var i = 0; i < 35; i++) {
      await tester.drag(find.byType(Scrollable).first, const Offset(0, -500));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    }
  }

  testWidgets(
    'overview RTL exact authoritative totals and explicit accounting labels',
    (tester) async {
      await show(tester);
      await tester.pumpAndSettle();
      expect(
        Directionality.of(tester.element(find.text('المبيعات'))),
        TextDirection.rtl,
      );
      expect(find.text('9,007,199,254,740,993.125 د.ك'), findsOneWidget);
      expect(find.text('4,503,599,627,370,497.125 د.ك'), findsOneWidget);
      expect(find.text('555.987 د.ك'), findsOneWidget);
      expect(find.textContaining('هذه ليست مبالغ التحصيل'), findsOneWidget);
      expect(find.text('متوسط الفاتورة قبل المرتجعات'), findsOneWidget);
      expect(find.textContaining('2026-10-31'), findsWidgets);
    },
  );
  testWidgets('trend uses actual daily rows and top products retain units', (
    tester,
  ) async {
    await show(tester);
    await tester.pumpAndSettle();
    await reveal(tester, find.text('اتجاه صافي المبيعات اليومي'));
    await tester.tap(find.text('اتجاه صافي المبيعات اليومي'));
    await tester.pumpAndSettle();
    expect(find.text('2026-10-01'), findsOneWidget);
    expect(find.text('2026-10-31'), findsOneWidget);
    await bottom(tester);
    expect(find.text('1.500 قطعة'), findsOneWidget);
    expect(find.text('222.987 د.ك'), findsOneWidget);
  });
  testWidgets(
    'no-profit over-return cannot appear in text or accessibility semantics',
    (tester) async {
      await show(tester, permissions: ['REPORTS_VIEW', 'REPORTS_SALES']);
      await tester.pumpAndSettle();
      final semantics = tester.ensureSemantics();
      expect(find.text('555.987 د.ك'), findsNothing);
      expect(find.text('222.987 د.ك'), findsNothing);
      expect(find.text('صافي الربح بعد المصروفات'), findsNothing);
      expect(
        find.bySemanticsLabel(RegExp(r'555\.987|222\.987|333\.987')),
        findsNothing,
      );
      expect(find.byType(InvoiceList), findsNothing);
      semantics.dispose();
    },
  );
  testWidgets(
    'zero summary and empty top list are legitimate, negative net remains signed',
    (tester) async {
      await show(
        tester,
        handler: (_, p, _, _) async {
          final raw = salesResponse(p) as Map<String, Object?>;
          if (p.startsWith('manager/sales/summary')) {
            raw.addAll({
              'grossSales': '0.000',
              'returns': '2.125',
              'netSales': '-2.125',
              'invoiceCount': 0,
              'returnCount': 1,
              'averageInvoice': '0.000',
            });
          }
          if (p.startsWith('manager/sales/top-products')) raw['items'] = [];
          return raw;
        },
      );
      await tester.pumpAndSettle();
      expect(find.text('-2.125 د.ك'), findsOneWidget);
      expect(find.text('0.000 د.ك'), findsWidgets);
      await reveal(tester, find.text('لا توجد أصناف مباعة في هذه الفترة'));
      expect(find.text('إعادة المحاولة'), findsNothing);
    },
  );
  testWidgets('list search debounces, clear removes server q', (tester) async {
    final f = await show(tester, screen: 'list');
    await tester.pumpAndSettle();
    f.transport.calls.clear();
    await tester.enterText(find.byType(TextField), 'INV-1');
    await tester.pump(const Duration(milliseconds: 200));
    expect(f.transport.calls, isEmpty);
    await tester.pump(const Duration(milliseconds: 200));
    await tester.pumpAndSettle();
    expect(
      Uri.parse(f.transport.calls.last.path).queryParameters['q'],
      'INV-1',
    );
    await tester.tap(find.byTooltip('مسح البحث'));
    await tester.pumpAndSettle();
    expect(
      Uri.parse(f.transport.calls.last.path).queryParameters.containsKey('q'),
      isFalse,
    );
  });
  for (final search in [false, true]) {
    testWidgets('empty invoice search=$search distinct from failed request', (
      tester,
    ) async {
      await show(
        tester,
        screen: 'list',
        handler: (_, _, _, _) async => inventoryPage([]),
      );
      await tester.pumpAndSettle();
      if (search) {
        await tester.enterText(find.byType(TextField), 'not found');
        await tester.pump(const Duration(milliseconds: 400));
        await tester.pumpAndSettle();
      }
      expect(
        find.text(
          search
              ? 'لا توجد نتائج مطابقة'
              : 'لا توجد فواتير مطابقة لهذه الفترة والمرشحات',
        ),
        findsOneWidget,
      );
      expect(find.text('إعادة المحاولة'), findsNothing);
    });
  }
  testWidgets(
    'filters send actual enums and All resets nullable status/payment',
    (tester) async {
      final f = await show(tester, screen: 'list');
      await tester.pumpAndSettle();
      final status = find.byType(DropdownButtonFormField<InvoiceStatus?>);
      await reveal(tester, status);
      await tester.tap(status);
      await tester.pumpAndSettle();
      await tester.tap(find.text('مسودة').last);
      await tester.pumpAndSettle();
      expect(
        Uri.parse(f.transport.calls.last.path).queryParameters['status'],
        'DRAFT',
      );
      await tester.tap(find.byType(DropdownButtonFormField<InvoiceStatus?>));
      await tester.pumpAndSettle();
      await tester.tap(find.text('كل الحالات').last);
      await tester.pumpAndSettle();
      expect(
        Uri.parse(f.transport.calls.last.path).queryParameters
            .containsKey('status'),
        isFalse,
      );
      final payment = find.byType(DropdownButtonFormField<InvoicePayment?>);
      await reveal(tester, payment);
      await tester.tap(payment);
      await tester.pumpAndSettle();
      await tester.tap(find.text('آجل').last);
      await tester.pumpAndSettle();
      expect(
        Uri.parse(f.transport.calls.last.path).queryParameters['paymentMethod'],
        'CREDIT',
      );
      await tester.tap(find.byType(DropdownButtonFormField<InvoicePayment?>));
      await tester.pumpAndSettle();
      await tester.tap(find.text('كل طرق الدفع').last);
      await tester.pumpAndSettle();
      expect(
        Uri.parse(f.transport.calls.last.path).queryParameters
            .containsKey('paymentMethod'),
        isFalse,
      );
    },
  );
  testWidgets('custom invalid date stays in dialog; valid dates reach server', (
    tester,
  ) async {
    final f = await show(tester);
    await tester.pumpAndSettle();
    await tester.tap(find.text('تحديد تاريخين'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextFormField).at(0), '2026-02-30');
    await tester.enterText(find.byType(TextFormField).at(1), '2026-10-07');
    await tester.tap(find.text('تطبيق'));
    await tester.pumpAndSettle();
    expect(find.textContaining('أدخل تاريخين صحيحين'), findsOneWidget);
    await tester.enterText(find.byType(TextFormField).at(0), '2026-10-01');
    await tester.tap(find.text('تطبيق'));
    await tester.pumpAndSettle();
    expect(find.byType(AlertDialog), findsNothing);
    expect(
      f.transport.calls.any(
        (c) =>
            c.path ==
            'manager/sales/summary?period=custom&from=2026-10-01&to=2026-10-07',
      ),
      isTrue,
    );
  });
  testWidgets(
    'invoice opens real detail; customer reference returns to server-filtered list; Android back',
    (tester) async {
      final f = await show(tester, screen: 'list');
      await tester.pumpAndSettle();
      await reveal(tester, find.text('INV-1'));
      await tester.tap(find.text('INV-1'));
      await tester.pumpAndSettle();
      expect(find.text('تفاصيل الفاتورة'), findsOneWidget);
      expect(f.transport.calls.last.path, startsWith('manager/invoices/1?'));
      await tester.tap(find.text('فواتير هذا العميل'));
      await tester.pumpAndSettle();
      expect(
        Uri.parse(f.transport.calls.last.path).queryParameters['customerId'],
        '1',
      );
      await reveal(tester, find.text('كل العملاء'));
      await tester.tap(find.text('كل العملاء'));
      await tester.pumpAndSettle();
      expect(
        Uri.parse(f.transport.calls.last.path).queryParameters
            .containsKey('customerId'),
        isFalse,
      );
      await reveal(tester, find.text('INV-1'));
      await tester.tap(find.text('INV-1'));
      await tester.pumpAndSettle();
      await tester.pageBack();
      await tester.pumpAndSettle();
      expect(find.byType(InvoiceList), findsOneWidget);
    },
  );
  testWidgets(
    'detail shows original tax/payment separately from all-time return/refund and historical values',
    (tester) async {
      await show(tester, screen: 'detail');
      await tester.pumpAndSettle();
      expect(find.text('الضريبة المخزنة'), findsOneWidget);
      expect(find.text('2.500 د.ك'), findsOneWidget);
      expect(find.text('6.375 د.ك'), findsOneWidget);
      expect(find.text('9.000 د.ك'), findsOneWidget);
      expect(find.text('2.375 د.ك'), findsOneWidget);
      expect(find.text('7.987 د.ك'), findsOneWidget);
      expect(find.text('3.388 د.ك'), findsOneWidget);
      expect(find.textContaining('ليس مديونية العميل الحالية'), findsOneWidget);
      expect(find.textContaining('قيمة المرتجع تختلف'), findsOneWidget);
      expect(find.text('9.500 د.ك'), findsNWidgets(3));
      expect(find.text('1.987 د.ك'), findsNWidgets(3));
      expect(find.text('2026-11-05 12:00:00'), findsOneWidget);
      expect(
        tester
            .widget<IconButton>(
              find.byWidgetPredicate(
                (w) => w is IconButton && w.tooltip == 'تحديث الفاتورة',
              ),
            )
            .onPressed,
        isNotNull,
      );
    },
  );
  testWidgets(
    'detail missing authorized optional cost/profit is absent, never zero fallback',
    (tester) async {
      await show(
        tester,
        screen: 'detail',
        handler: (_, _, _, _) async {
          final raw = detailInvoiceJson();
          (raw['invoice'] as Map)
            ..remove('historicalCost')
            ..remove('grossProfit');
          for (final item in (raw['items'] as Map)['items'] as List) {
            (item as Map).remove('historicalUnitCost');
          }
          return raw;
        },
      );
      await tester.pumpAndSettle();
      expect(find.text('القيم التاريخية'), findsNothing);
      expect(find.text('تكلفة الوحدة التاريخية'), findsNothing);
    },
  );
  testWidgets(
    'detail over-returned forbidden finances absent from UI and semantics',
    (tester) async {
      await show(
        tester,
        screen: 'detail',
        permissions: ['SALES_VIEW', 'REPORTS_PROFIT', 'PRODUCT_COST'],
      );
      await tester.pumpAndSettle();
      final semantics = tester.ensureSemantics();
      for (final text in [
        '7.987 د.ك',
        '3.388 د.ك',
        '1.987 د.ك',
        'القيم التاريخية',
      ]) {
        expect(find.text(text), findsNothing);
      }
      expect(
        find.bySemanticsLabel(RegExp(r'7\.987|3\.388|1\.987')),
        findsNothing,
      );
      semantics.dispose();
    },
  );
  testWidgets('loaded detail disappears immediately on /me permission loss', (
    tester,
  ) async {
    final f = await show(tester, screen: 'detail');
    await tester.pumpAndSettle();
    expect(find.text('7.987 د.ك'), findsOneWidget);
    f.transport.handler = (_, p, _, _) async =>
        p == 'auth/me' ? userJson(permissions: []) : salesResponse(p);
    await f.auth.revalidate();
    await tester.pumpAndSettle();
    expect(find.text('7.987 د.ك'), findsNothing);
    expect(find.text('INV-1'), findsNothing);
    expect(find.textContaining('غير متاحة ضمن صلاحيات'), findsOneWidget);
  });
  for (final code in [403, 404]) {
    testWidgets('loaded detail refresh $code erases prior financial UI', (
      tester,
    ) async {
      final f = await show(tester, screen: 'detail');
      await tester.pumpAndSettle();
      f.transport.handler = (_, p, _, _) async => p == 'auth/me'
          ? userJson(permissions: salesPermissions)
          : throw AppFailure.http(code, null);
      await tester.tap(find.byTooltip('تحديث الفاتورة'));
      await tester.pumpAndSettle();
      expect(find.text('7.987 د.ك'), findsNothing);
      expect(find.text('INV-1'), findsNothing);
      expect(find.text('إعادة المحاولة'), findsOneWidget);
    });
  }
  for (final permissions in [
    <String>['SALES_VIEW'],
    <String>['REPORTS_VIEW', 'REPORTS_SALES'],
    <String>[],
  ]) {
    testWidgets('shell permission navigation ${permissions.join(',')}', (
      tester,
    ) async {
      final f = await show(tester, shell: true, permissions: permissions);
      await tester.pumpAndSettle();
      await tester.tap(find.byIcon(Icons.menu));
      await tester.pumpAndSettle();
      expect(
        find.text('المبيعات والفواتير'),
        permissions.isEmpty ? findsNothing : findsOneWidget,
      );
      if (permissions.isNotEmpty) {
        await tester.tap(find.text('المبيعات والفواتير'));
        await tester.pumpAndSettle();
        expect(find.byType(SalesScreen), findsOneWidget);
        expect(
          f.transport.calls
              .where((c) => c.path.startsWith('manager/'))
              .every((c) => c.method == 'GET'),
          isTrue,
        );
      }
    });
  }
  for (final invoices in [false, true]) {
    testWidgets(
      'dashboard action opens real ${invoices ? 'invoices' : 'overview'} destination',
      (tester) async {
        final f = await show(tester, shell: true);
        await tester.pumpAndSettle();
        final label = invoices ? 'عرض الفواتير' : 'عرض ملخص المبيعات';
        await reveal(tester, find.text(label));
        await tester.tap(find.text(label));
        await tester.pumpAndSettle();
        expect(find.byType(SalesScreen), findsOneWidget);
        expect(
          f.transport.calls.any(
            (c) => c.path.startsWith(
              invoices ? 'manager/invoices?' : 'manager/sales/summary?',
            ),
          ),
          isTrue,
        );
      },
    );
  }
  for (final screen in ['overview', 'list', 'detail']) {
    testWidgets('$screen loading, safe error and successful retry', (
      tester,
    ) async {
      final gate = Completer<Object?>();
      final f = await show(
        tester,
        screen: screen,
        handler: (_, _, _, _) => gate.future,
      );
      await tester.pump();
      expect(find.byType(LinearProgressIndicator), findsWidgets);
      expect(find.text('INV-1'), findsNothing);
      gate.completeError(
        AppFailure.http(503, {'message': 'sensitive sql text'}),
      );
      await tester.pumpAndSettle();
      expect(find.textContaining('sensitive sql'), findsNothing);
      f.transport.handler = (_, p, _, _) async => salesResponse(p);
      await reveal(tester, find.text('إعادة المحاولة'));
      await tester.tap(find.text('إعادة المحاولة'));
      await tester.pumpAndSettle();
      expect(find.text('إعادة المحاولة'), findsNothing);
    });
  }
  testWidgets(
    'detail independently paginates real nested collections without replacing each other',
    (tester) async {
      final f = await show(
        tester,
        screen: 'detail',
        handler: (_, p, _, _) async {
          final q = Uri.parse(p).queryParameters,
              page = int.parse(q['page']!),
              size = int.parse(q['size']!),
              returnsPage = int.parse(q['returnsPage']!),
              returnsSize = int.parse(q['returnsSize']!);
          return {
            'invoice': invoiceJson(1, detail: true),
            'items': inventoryPage(
              [for (var id = 1; id <= 21; id++) invoiceLineJson(id)]
                  .skip(page * size)
                  .take(size)
                  .toList(),
              page: page,
              size: size,
              total: 21,
            ),
            'returns': inventoryPage(
              [for (var id = 1; id <= 21; id++) invoiceReturnJson(id)]
                  .skip(returnsPage * returnsSize)
                  .take(returnsSize)
                  .toList(),
              page: returnsPage,
              size: returnsSize,
              total: 21,
            ),
          };
        },
      );
      await tester.pumpAndSettle();
      expect(find.text('تم تحميل 20 من 21'), findsNWidgets(2));
      await reveal(tester, find.text('تحميل المزيد من البنود'));
      await tester.tap(find.text('تحميل المزيد من البنود'));
      await tester.pumpAndSettle();
      expect(find.text('تم تحميل 21 من 21'), findsOneWidget);
      expect(find.text('تم تحميل 20 من 21'), findsOneWidget);
      expect(Uri.parse(f.transport.calls.last.path).queryParameters, {
        'page': '1',
        'size': '20',
        'returnsPage': '0',
        'returnsSize': '20',
      });
      await reveal(tester, find.text('تحميل المزيد من المرتجعات'));
      await tester.tap(find.text('تحميل المزيد من المرتجعات'));
      await tester.pumpAndSettle();
      expect(find.text('تم تحميل 21 من 21'), findsNWidgets(2));
      expect(find.text('RET-21'), findsOneWidget);
      expect(Uri.parse(f.transport.calls.last.path).queryParameters, {
        'page': '0',
        'size': '20',
        'returnsPage': '1',
        'returnsSize': '20',
      });
    },
  );
  testWidgets(
    'detail empty lines and returns have distinct legitimate states',
    (tester) async {
      await show(
        tester,
        screen: 'detail',
        handler: (_, _, _, _) async => {
          'invoice': invoiceJson(1, detail: true),
          'items': inventoryPage([]),
          'returns': inventoryPage([]),
        },
      );
      await tester.pumpAndSettle();
      expect(find.text('لا توجد بنود لهذه الفاتورة'), findsOneWidget);
      expect(find.text('لا توجد مرتجعات مسجلة لهذه الفاتورة'), findsOneWidget);
      expect(find.text('إعادة المحاولة'), findsNothing);
    },
  );
  for (final screen in ['overview', 'list', 'detail']) {
    testWidgets('$screen narrow safe error and retry scale1.5 accessible', (
      tester,
    ) async {
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
      await reveal(tester, find.text('إعادة المحاولة'));
      expect(tester.takeException(), isNull);
      expect(find.text('إعادة المحاولة').hitTestable(), findsOneWidget);
    });
  }
  for (final size in [
    const Size(320, 700),
    const Size(390, 844),
    const Size(800, 1024),
  ]) {
    for (final screen in ['overview', 'list', 'detail']) {
      testWidgets(
        '$screen RTL ${size.width} scale1.5 no overflow at top/bottom large exact values',
        (tester) async {
          tester.view.physicalSize = size;
          tester.view.devicePixelRatio = 1;
          tester.platformDispatcher.textScaleFactorTestValue = 1.5;
          addTearDown(() {
            tester.view.resetPhysicalSize();
            tester.view.resetDevicePixelRatio();
            tester.platformDispatcher.clearTextScaleFactorTestValue();
          });
          await show(tester, screen: screen);
          await tester.pumpAndSettle();
          expect(tester.takeException(), isNull);
          await bottom(tester);
          if (screen == 'list') {
            expect(find.text('9,007,199,254,740,993.125 د.ك'), findsOneWidget);
            expect(
              find.text('INV-2026-IDENTIFIER-000000000000000000000000003'),
              findsOneWidget,
            );
            expect(
              find.ancestor(
                of: find.text(
                  'INV-2026-IDENTIFIER-000000000000000000000000003',
                ),
                matching: find.byType(FittedBox),
              ),
              findsNothing,
            );
          }
          if (screen == 'detail') {
            expect(find.text('9,007,199,254,740,993.125 قطعة'), findsOneWidget);
          }
          expect(tester.takeException(), isNull);
        },
      );
    }
  }
}
