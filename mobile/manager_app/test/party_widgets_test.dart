import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:manager_app/app/manager_app.dart';
import 'package:manager_app/core/errors/app_failure.dart';
import 'package:manager_app/core/theme/app_theme.dart';
import 'package:manager_app/features/auth/state/auth_controller.dart';
import 'package:manager_app/features/parties/data/party_access.dart';
import 'package:manager_app/features/parties/presentation/party_screen.dart';
import 'package:manager_app/features/parties/presentation/party_detail_screen.dart';
import 'package:manager_app/features/parties/presentation/party_account_screen.dart';

import 'support/fakes.dart';
import 'support/inventory_fixtures.dart';
import 'support/party_fixtures.dart';

void main() {
  Future<({AuthController auth, FakeTransport transport})> show(
    WidgetTester tester,
    PartyKind kind, {
    String screen = 'list',
    List<String> permissions = partyPermissions,
    Handler? handler,
    bool shell = false,
    double scale = 1,
  }) async {
    final f = await partyAuth(permissions: permissions);
    addTearDown(f.auth.dispose);
    if (handler != null) f.transport.handler = handler;
    await tester.pumpWidget(
      shell
          ? ManagerApp(auth: f.auth, monitorSession: false)
          : MaterialApp(
              theme: managerTheme(Brightness.light),
              builder: (context, child) => MediaQuery(
                data: MediaQuery.of(context)
                    .copyWith(textScaler: TextScaler.linear(scale)),
                child: Directionality(
                  textDirection: TextDirection.rtl,
                  child: child!,
                ),
              ),
              home: screen == 'detail'
                  ? PartyDetailScreen(auth: f.auth, kind: kind, id: 1)
                  : screen == 'account'
                  ? PartyAccountScreen(auth: f.auth, kind: kind, id: 1)
                  : Scaffold(
                      body: PartyScreen(auth: f.auth, kind: kind),
                    ),
            ),
    );
    return f;
  }

  Future<void> reveal(WidgetTester tester, Finder finder) async {
    await tester.scrollUntilVisible(
      finder,
      300,
      scrollable: find.byType(Scrollable).first,
      maxScrolls: 80,
    );
    await tester.pumpAndSettle();
  }

  Future<void> bottom(WidgetTester tester) async {
    for (var i = 0; i < 12; i++) {
      await tester.drag(find.byType(Scrollable).first, const Offset(0, -600));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    }
  }

  for (final kind in PartyKind.values) {
    final prefix = 'manager/${kind.path}',
        name = '${kind == PartyKind.customer ? 'عميل' : 'مورد'} الاختبار 1';
    testWidgets(
      '${kind.name}: real list identity, signed balance, Arabic RTL and details navigation',
      (tester) async {
        await show(tester, kind);
        await tester.pumpAndSettle();
        expect(
          Directionality.of(tester.element(find.text(kind.title))),
          TextDirection.rtl,
        );
        await reveal(tester, find.text(name));
        expect(find.text('12345678'), findsOneWidget);
        await reveal(
          tester,
          find.text(kind == PartyKind.customer ? '-2.000 د.ك' : '-1.000 د.ك'),
        );
        await tester.drag(find.byType(Scrollable).first, const Offset(0, 2000));
        await tester.pumpAndSettle();
        await reveal(tester, find.text(name));
        await tester.tap(find.text(name));
        await tester.pumpAndSettle();
        expect(find.text('بيانات ${kind.singular}'), findsOneWidget);
        expect(find.text('الكويت'), findsOneWidget);
        await reveal(tester, find.text('عرض كشف الحساب'));
        await tester.tap(find.text('عرض كشف الحساب'));
        await tester.pumpAndSettle();
        expect(find.text('كشف حساب ${kind.singular}'), findsOneWidget);
        await tester.pageBack();
        await tester.pumpAndSettle();
        expect(find.text('بيانات ${kind.singular}'), findsOneWidget);
      },
    );
    testWidgets(
      '${kind.name}: debt view displays authoritative aggregate and inactive parties',
      (tester) async {
        final f = await show(tester, kind);
        await tester.pumpAndSettle();
        await tester.tap(find.text(kind.debtTitle));
        await tester.pumpAndSettle();
        expect(
          find.text('إجمالي ${kind.debtTitle} • كل نتائج البحث'),
          findsOneWidget,
        );
        expect(
          find.text(kind == PartyKind.customer ? '28.875 د.ك' : '11.000 د.ك'),
          findsOneWidget,
        );
        await reveal(tester, find.text('غير نشط'));
        expect(find.text('غير نشط'), findsOneWidget);
        expect(
          f.transport.calls.any(
            (c) => Uri.parse(c.path).path == '$prefix/${kind.debtPath}',
          ),
          isTrue,
        );
      },
    );
    testWidgets(
      '${kind.name}: server-backed debounced search, clear and fixed sort',
      (tester) async {
        final f = await show(tester, kind);
        await tester.pumpAndSettle();
        await tester.enterText(find.byType(TextField), 'غير موجود');
        await tester.pump();
        expect(find.text('جارٍ البحث…'), findsOneWidget);
        await tester.pump(const Duration(milliseconds: 400));
        await tester.pumpAndSettle();
        expect(find.text('لا توجد نتائج للبحث.'), findsOneWidget);
        expect(
          Uri.parse(f.transport.calls.last.path).queryParameters['q'],
          'غير موجود',
        );
        await tester.tap(find.byTooltip('مسح البحث'));
        await tester.pumpAndSettle();
        expect(
          Uri.parse(f.transport.calls.last.path).queryParameters
              .containsKey('q'),
          isFalse,
        );
        await tester.tap(find.text('الاسم أ–ي'));
        await tester.pumpAndSettle();
        await tester.tap(find.text('الكود تنازلياً').last);
        await tester.pumpAndSettle();
        expect(
          Uri.parse(f.transport.calls.last.path).queryParameters['sort'],
          'code,desc',
        );
      },
    );
    testWidgets(
      '${kind.name}: identity-only list and detail omit money and semantics even if over-returned',
      (tester) async {
        final semantics = tester.ensureSemantics();
        try {
          await show(tester, kind, permissions: [kind.identityCode]);
          await tester.pumpAndSettle();
          await bottom(tester);
          expect(find.textContaining('د.ك'), findsNothing);
          expect(find.text('الرصيد الحالي'), findsNothing);
          expect(find.text(kind.debtTitle), findsNothing);
          expect(
            tester
                .binding
                .renderViews
                .single
                .owner!
                .semanticsOwner!
                .rootSemanticsNode!
                .toStringDeep(),
            isNot(contains('8.875')),
          );
          await show(
            tester,
            kind,
            permissions: [kind.identityCode],
            screen: 'detail',
          );
          await tester.pumpAndSettle();
          expect(find.text('عرض كشف الحساب'), findsNothing);
          expect(find.textContaining('د.ك'), findsNothing);
          expect(
            tester
                .binding
                .renderViews
                .single
                .owner!
                .semanticsOwner!
                .rootSemanticsNode!
                .toStringDeep(),
            isNot(contains('7.000')),
          );
        } finally {
          semantics.dispose();
        }
      },
    );
    testWidgets(
      '${kind.name}: permission loss removes previous sensitive text and accessibility immediately',
      (tester) async {
        final f = await show(tester, kind, screen: 'detail');
        await tester.pumpAndSettle();
        expect(find.text('الرصيد الحالي'), findsOneWidget);
        final semantics = tester.ensureSemantics(),
            reload = Completer<Object?>();
        try {
          f.transport.handler = (_, p, _, _) => p == 'auth/me'
              ? Future.value(userJson(permissions: [kind.identityCode]))
              : reload.future;
          await f.auth.revalidate();
          await tester.pump();
          expect(find.textContaining('د.ك'), findsNothing);
          expect(find.text(name), findsNothing);
          expect(
            tester
                .binding
                .renderViews
                .single
                .owner!
                .semanticsOwner!
                .rootSemanticsNode!
                .toStringDeep(),
            isNot(contains('8.875')),
          );
          reload.complete(partyJson(kind, 1));
          await tester.pumpAndSettle();
          expect(find.text(name), findsOneWidget);
          expect(find.text('عرض كشف الحساب'), findsNothing);
        } finally {
          semantics.dispose();
        }
      },
    );
    testWidgets(
      '${kind.name}: account shows brought-forward, complete closing and actual references',
      (tester) async {
        await show(tester, kind, screen: 'account');
        await tester.pumpAndSettle();
        expect(find.text('الرصيد المرحّل قبل بداية الفترة'), findsOneWidget);
        expect(find.text('الرصيد الختامي • كامل الفترة'), findsOneWidget);
        await reveal(
          tester,
          find.text('REF-2026-000000000000000000000000000001'),
        );
        expect(
          find.text(kind == PartyKind.customer ? 'فاتورة بيع' : 'فاتورة شراء'),
          findsOneWidget,
        );
        expect(find.text('مدين'), findsWidgets);
        expect(find.text('دائن'), findsWidgets);
        await bottom(tester);
        expect(find.text('نهاية النتائج'), findsOneWidget);
      },
    );
    for (final screen in ['list', 'detail', 'account']) {
      testWidgets('${kind.name}: $screen loading, safe failure and retry', (
        tester,
      ) async {
        final gate = Completer<Object?>();
        final f = await show(
          tester,
          kind,
          screen: screen,
          handler: (_, _, _, _) => gate.future,
        );
        await tester.pump();
        expect(find.byType(LinearProgressIndicator), findsOneWidget);
        gate.completeError(
          AppFailure.http(503, {'message': 'SQL private 777.987'}),
        );
        await tester.pumpAndSettle();
        expect(find.textContaining('SQL'), findsNothing);
        expect(find.textContaining('777.987'), findsNothing);
        expect(find.text('إعادة المحاولة'), findsOneWidget);
        f.transport.handler = (_, p, _, _) async => partyResponse(p);
        await reveal(tester, find.text('إعادة المحاولة'));
        await tester.tap(find.text('إعادة المحاولة'));
        await tester.pumpAndSettle();
        expect(find.text('إعادة المحاولة'), findsNothing);
      });
    }
    testWidgets(
      '${kind.name}: empty party, debt and account states are distinct',
      (tester) async {
        await show(
          tester,
          kind,
          handler: (_, _, _, _) async => {
            'page': inventoryPage([], total: 0),
            'totalOutstanding': '0.000',
          },
        );
        await tester.pumpAndSettle();
        expect(
          find.text(
            kind == PartyKind.customer ? 'لا يوجد عملاء.' : 'لا يوجد موردون.',
          ),
          findsOneWidget,
        );
        await tester.tap(find.text(kind.debtTitle));
        await tester.pumpAndSettle();
        expect(find.text('لا توجد أرصدة مستحقة ضمن النتائج.'), findsOneWidget);
        await show(
          tester,
          kind,
          screen: 'account',
          handler: (_, _, _, _) async => {
            ...partyAccountJson(kind),
            'entries': inventoryPage([], total: 0),
          },
        );
        await tester.pumpAndSettle();
        await reveal(tester, find.text('لا توجد حركات في الفترة المحددة.'));
        expect(find.text('لا توجد حركات في الفترة المحددة.'), findsOneWidget);
      },
    );
    testWidgets(
      '${kind.name}: custom statement dates validate and submit only account parameters',
      (tester) async {
        final f = await show(tester, kind, screen: 'account');
        await tester.pumpAndSettle();
        await tester.tap(find.text('تحديد تاريخين'));
        await tester.pumpAndSettle();
        await tester.enterText(find.byType(TextFormField).first, '2026-02-30');
        await tester.enterText(find.byType(TextFormField).last, '2026-10-07');
        await tester.tap(find.text('تطبيق'));
        await tester.pumpAndSettle();
        expect(find.textContaining('أدخل تاريخين صحيحين'), findsOneWidget);
        await tester.enterText(find.byType(TextFormField).first, '2026-10-01');
        await tester.tap(find.text('تطبيق'));
        await tester.pumpAndSettle();
        final q = Uri.parse(f.transport.calls.last.path).queryParameters;
        expect(q['from'], '2026-10-01');
        expect(q['to'], '2026-10-07');
        expect(q.containsKey('q'), isFalse);
      },
    );
    for (final screen in ['list', 'account']) {
      testWidgets('${kind.name}: $screen failed next-page retries same page', (
        tester,
      ) async {
        var fail = true;
        final f = await show(
          tester,
          kind,
          screen: screen,
          handler: (_, p, _, _) async {
            final q = Uri.parse(p).queryParameters,
                page = int.parse(q['page'] ?? '0');
            if (page == 1 && fail) throw AppFailure.http(503, null);
            final rows = [
              for (var i = page * 20; i < (page == 0 ? 20 : 21); i++)
                screen == 'list' ? partyJson(kind, i + 1) : partyEntry(kind, 1),
            ];
            final data = inventoryPage(rows, page: page, size: 20, total: 21);
            return screen == 'list'
                ? {'page': data}
                : {...partyAccountJson(kind), 'entries': data};
          },
        );
        await tester.pumpAndSettle();
        await reveal(tester, find.text('تحميل المزيد'));
        await tester.tap(find.text('تحميل المزيد'));
        await tester.pumpAndSettle();
        expect(find.text('إعادة المحاولة'), findsOneWidget);
        fail = false;
        await reveal(tester, find.text('إعادة المحاولة'));
        await tester.tap(find.text('إعادة المحاولة'));
        await tester.pumpAndSettle();
        expect(
          Uri.parse(f.transport.calls.last.path).queryParameters['page'],
          '1',
        );
        await reveal(tester, find.text('نهاية النتائج'));
        expect(find.text('نهاية النتائج'), findsOneWidget);
      });
    }
    for (final screen in ['list', 'detail', 'account']) {
      for (final size in [
        const Size(320, 700),
        const Size(390, 844),
        const Size(800, 1024),
      ]) {
        testWidgets(
          '${kind.name}: $screen RTL $size scale1.5 large decimals and references never overflow',
          (tester) async {
            tester.view.physicalSize = size;
            tester.view.devicePixelRatio = 1;
            addTearDown(tester.view.resetPhysicalSize);
            addTearDown(tester.view.resetDevicePixelRatio);
            await show(
              tester,
              kind,
              screen: screen,
              scale: 1.5,
              handler: (_, p, _, _) async {
                final raw = partyResponse(p);
                if (screen == 'detail') {
                  return {
                    ...partyJson(kind, 1),
                    'balance': '-9007199254740993.125',
                    'code':
                        'LONG-IDENTIFIER-000000000000000000000000000000000001',
                  };
                }
                if (screen == 'account') {
                  return {
                    ...partyAccountJson(kind),
                    'closingBalance': '-9007199254740993.125',
                  };
                }
                final j = raw as Map<String, Object?>;
                return {
                  ...j,
                  'page': inventoryPage([
                    {
                      ...partyJson(kind, 1),
                      'balance': '-9007199254740993.125',
                      'code': 'LONG-IDENTIFIER-000000000000000000000000000000000001',
                    },
                  ], total: 1),
                };
              },
            );
            await tester.pumpAndSettle();
            await bottom(tester);
            expect(tester.takeException(), isNull);
            expect(
              find
                  .byType(ListView)
                  .evaluate()
                  .every(
                    (e) =>
                        (e.widget as ListView).scrollDirection == Axis.vertical,
                  ),
              isTrue,
            );
          },
        );
      }
    }
  }
  testWidgets(
    'shell exposes separate authorized parties and preserves old destinations',
    (tester) async {
      await show(tester, PartyKind.customer, shell: true);
      await tester.pumpAndSettle();
      await tester.tap(find.byIcon(Icons.menu));
      await tester.pumpAndSettle();
      for (final label in [
        'لوحة المتابعة',
        'المنتجات والمخزون',
        'المبيعات والفواتير',
        'العملاء',
        'الموردون',
        'الملف الشخصي والأجهزة',
      ]) {
        expect(
          find.descendant(of: find.byType(Drawer), matching: find.text(label)),
          findsOneWidget,
        );
      }
      await tester.tap(find.text('الموردون'));
      await tester.pumpAndSettle();
      expect(find.text('الموردون'), findsOneWidget);
      await tester.tap(find.byIcon(Icons.menu));
      await tester.pumpAndSettle();
      await tester.tap(find.text('العملاء'));
      await tester.pumpAndSettle();
      expect(find.text('العملاء'), findsOneWidget);
    },
  );
  testWidgets(
    'identity-only shell hides other party and stale navigation loses access',
    (tester) async {
      final f = await show(
        tester,
        PartyKind.customer,
        shell: true,
        permissions: ['CUSTOMERS_VIEW'],
      );
      await tester.pumpAndSettle();
      expect(find.text('العملاء'), findsOneWidget);
      await tester.tap(find.byIcon(Icons.menu));
      await tester.pumpAndSettle();
      expect(find.text('الموردون'), findsNothing);
      await tester.tap(find.text('العملاء').last);
      await tester.pumpAndSettle();
      f.transport.handler = (_, p, _, _) async => p == 'auth/me'
          ? userJson(permissions: ['DASHBOARD'])
          : partyResponse(p);
      await f.auth.revalidate();
      await tester.pumpAndSettle();
      expect(find.text('عميل الاختبار 1'), findsNothing);
      await tester.tap(find.byIcon(Icons.menu));
      await tester.pumpAndSettle();
      expect(find.text('العملاء'), findsNothing);
    },
  );
  for (final kind in PartyKind.values) {
    testWidgets(
      'Dashboard authorized ${kind.name} balance action opens actual debt view',
      (tester) async {
        final f = await show(tester, kind, shell: true);
        await tester.pumpAndSettle();
        final label = kind == PartyKind.customer
            ? 'عرض ديون العملاء'
            : 'عرض مستحقات الموردين';
        await reveal(tester, find.text(label));
        await tester.tap(find.text(label));
        await tester.pumpAndSettle();
        expect(find.text(kind.title), findsOneWidget);
        expect(
          f.transport.calls.any(
            (c) =>
                Uri.parse(c.path).path ==
                'manager/${kind.path}/${kind.debtPath}',
          ),
          isTrue,
        );
      },
    );
  }
  testWidgets('restricted and unauthorized account cannot publish money', (
    tester,
  ) async {
    await show(
      tester,
      PartyKind.customer,
      screen: 'account',
      permissions: ['CUSTOMERS_VIEW'],
    );
    await tester.pumpAndSettle();
    expect(find.text('ليست لديك صلاحية عرض هذه البيانات.'), findsOneWidget);
    expect(find.textContaining('د.ك'), findsNothing);
  });
}
