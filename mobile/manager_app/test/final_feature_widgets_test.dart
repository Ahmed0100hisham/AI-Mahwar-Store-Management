import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:manager_app/app/manager_app.dart';
import 'package:manager_app/core/errors/app_failure.dart';
import 'package:manager_app/core/theme/app_theme.dart';
import 'package:manager_app/features/auth/state/auth_controller.dart';
import 'package:manager_app/features/quotations/presentation/quotation_screen.dart';
import 'package:manager_app/features/reports/presentation/report_screen.dart';
import 'package:manager_app/features/audit/presentation/audit_screen.dart';
import 'package:manager_app/features/administration/data/admin_models.dart';
import 'package:manager_app/features/administration/presentation/admin_screen.dart';
import 'package:manager_app/features/administration/presentation/admin_editor.dart';

import 'support/final_feature_fixtures.dart';
import 'support/fakes.dart';

void main() {
  Future<
    ({AuthController auth, FakeTransport transport, AdminMetadata metadata})
  >
  show(
    WidgetTester tester,
    String screen, {
    List<String> permissions = finalPermissions,
    String role = 'ADMIN',
    Brightness brightness = Brightness.light,
    double scale = 1,
  }) async {
    final f = await finalAuth(permissions: permissions, role: role);
    addTearDown(f.auth.dispose);
    final metadata = AdminMetadata(
      adminRoles().map(AdminRole.fromJson).toList(),
      adminPermissions().map(AdminPermission.fromJson).toList(),
    );
    final child = switch (screen) {
      'quotation' => QuotationScreen(auth: f.auth),
      'detail' => QuotationDetailScreen(auth: f.auth, id: 1),
      'reports' => ReportScreen(auth: f.auth),
      'expense' => ReportReadScreen(auth: f.auth, kind: ReportKind.expenses),
      'cash' => ReportReadScreen(auth: f.auth, kind: ReportKind.cash),
      'daily' => ReportReadScreen(auth: f.auth, kind: ReportKind.daily),
      'slow' => SlowReportScreen(auth: f.auth),
      'audit' => AuditScreen(auth: f.auth),
      'users' => AdminScreen(auth: f.auth),
      'user' => AdminDetailScreen(auth: f.auth, id: 2, metadata: metadata),
      'create' => AdminEditor(auth: f.auth, metadata: metadata),
      'reset' => AdminEditor(
        auth: f.auth,
        metadata: metadata,
        target: AdminUser.fromJson(adminUser()),
        reset: true,
      ),
      _ => AdminEditor(
        auth: f.auth,
        metadata: metadata,
        target: AdminUser.fromJson(adminUser()),
      ),
    };
    await tester.pumpWidget(
      MaterialApp(
        theme: managerTheme(brightness),
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(context)
              .copyWith(textScaler: TextScaler.linear(scale)),
          child: Directionality(
            textDirection: TextDirection.rtl,
            child: child!,
          ),
        ),
        home: Scaffold(body: child),
      ),
    );
    await tester.pumpAndSettle();
    return (auth: f.auth, transport: f.transport, metadata: metadata);
  }

  Future<void> reveal(WidgetTester tester, Finder finder) async {
    await tester.scrollUntilVisible(
      finder,
      250,
      scrollable: find.byType(Scrollable).first,
    );
    await tester.ensureVisible(finder);
    await tester.pumpAndSettle();
  }

  for (final brightness in Brightness.values) {
    for (final size in [
      const Size(320, 700),
      const Size(390, 844),
      const Size(800, 1024),
    ]) {
      for (final screen in [
        'quotation',
        'detail',
        'reports',
        'expense',
        'cash',
        'daily',
        'slow',
        'audit',
        'users',
        'create',
        'reset',
        'user',
      ]) {
        testWidgets(
          '$screen RTL ${brightness.name} ${size.width} scale1.5 no overflow',
          (tester) async {
            await tester.binding.setSurfaceSize(size);
            addTearDown(() => tester.binding.setSurfaceSize(null));
            await show(tester, screen, brightness: brightness, scale: 1.5);
            expect(tester.takeException(), null);
            expect(
              Directionality.of(tester.element(find.byType(Scaffold).first)),
              TextDirection.rtl,
            );
            final scroll = find.byType(Scrollable).first;
            await tester.drag(scroll, const Offset(0, -500));
            await tester.pumpAndSettle();
            expect(tester.takeException(), null);
          },
        );
      }
    }
  }
  testWidgets(
    'quotation detail stored status and draft link do not invent conversion',
    (tester) async {
      await show(tester, 'detail');
      expect(find.text('مقبولة'), findsOneWidget);
      await reveal(tester, find.text('عرض الفاتورة المرتبطة'));
      expect(find.text('INV-1 • مسودة'), findsOneWidget);
      expect(find.textContaining('وجود الرابط لا يثبت'), findsOneWidget);
    },
  );
  testWidgets(
    'quotation link permission removed: visible data and semantics clear before reload',
    (tester) async {
      final f = await show(tester, 'detail');
      await reveal(tester, find.text('عرض الفاتورة المرتبطة'));
      final delayed = Completer<Object?>();
      f.transport.handler = (m, p, b, t) async => p == 'auth/me'
          ? finalUser(permissions: ['QUOTATIONS_VIEW'])
          : delayed.future;
      await f.auth.checkSession();
      await tester.pump();
      expect(find.textContaining('INV-1'), findsNothing);
      expect(find.text('عرض الفاتورة المرتبطة'), findsNothing);
      delayed.complete(finalResponse('manager/quotations/1'));
      await tester.pumpAndSettle();
      expect(find.textContaining('INV-1'), findsNothing);
    },
  );
  for (final screen in ['audit', 'users']) {
    testWidgets('$screen denied for forged nonadmin permissions', (
      tester,
    ) async {
      final f = await show(tester, screen, role: 'CASHIER');
      expect(find.text('ليست لديك صلاحية عرض هذه البيانات.'), findsOneWidget);
      expect(f.transport.calls, isEmpty);
    });
  }
  testWidgets(
    'shell retains inherited destinations and adds four authorized real destinations',
    (tester) async {
      final f = await finalAuth();
      addTearDown(f.auth.dispose);
      await tester.pumpWidget(ManagerApp(auth: f.auth, monitorSession: false));
      await tester.pumpAndSettle();
      await tester.tap(find.byIcon(Icons.menu));
      await tester.pumpAndSettle();
      for (final title in [
        'عروض الأسعار',
        'التقارير',
        'سجل العمليات',
        'إدارة المستخدمين',
        'الملف الشخصي والأجهزة',
      ]) {
        await reveal(tester, find.text(title));
        expect(find.text(title), findsOneWidget);
      }
      await tester.tap(find.text('إدارة المستخدمين'));
      await tester.pumpAndSettle();
      expect(find.text('مستخدم الاختبار 2'), findsOneWidget);
    },
  );
  testWidgets(
    'disable cancellation performs no write; confirmed identity posts once',
    (tester) async {
      final f = await show(tester, 'user');
      await reveal(
        tester,
        find.widgetWithText(OutlinedButton, 'تعطيل المستخدم'),
      );
      await tester.tap(find.widgetWithText(OutlinedButton, 'تعطيل المستخدم'));
      await tester.pumpAndSettle();
      expect(find.textContaining('user2\nمعرّف 2'), findsOneWidget);
      await tester.tap(find.text('إلغاء'));
      await tester.pumpAndSettle();
      expect(f.transport.calls.where((c) => c.method != 'GET'), isEmpty);
      await tester.tap(find.widgetWithText(OutlinedButton, 'تعطيل المستخدم'));
      await tester.pumpAndSettle();
      final delayed = Completer<Object?>();
      f.transport.handler = (m, p, b, t) async =>
          m == 'POST' ? delayed.future : finalResponse(p);
      await tester.tap(find.text('تأكيد'));
      await tester.pump();
      expect(f.transport.calls.where((c) => c.method == 'POST').length, 1);
      final button = tester.widget<OutlinedButton>(
        find.widgetWithText(OutlinedButton, 'تعطيل المستخدم'),
      );
      expect(button.onPressed, null);
      delayed.complete(adminUser()..['active'] = false);
      await tester.pumpAndSettle();
      expect(find.text('أكد الخادم تنفيذ العملية.'), findsOneWidget);
    },
  );
  testWidgets('reset password obscured and never submitted on cancel', (
    tester,
  ) async {
    final f = await show(tester, 'reset');
    final fields = find.byType(TextField);
    await tester.enterText(fields.at(0), 'FixturePass12');
    await tester.enterText(fields.at(1), 'FixturePass12');
    expect(tester.widget<TextField>(fields.at(0)).obscureText, true);
    expect(tester.widget<TextField>(fields.at(1)).obscureText, true);
    await reveal(
      tester,
      find.widgetWithText(FilledButton, 'إعادة تعيين كلمة المرور'),
    );
    await tester.tap(
      find.widgetWithText(FilledButton, 'إعادة تعيين كلمة المرور'),
    );
    await tester.pumpAndSettle();
    expect(find.textContaining('user2\nمعرّف 2'), findsOneWidget);
    await tester.tap(find.text('إلغاء'));
    await tester.pumpAndSettle();
    expect(f.transport.calls.where((c) => c.method != 'GET'), isEmpty);
    expect(find.text('FixturePass12'), findsNothing);
  });
  testWidgets('password forms clear immediately on permission loss', (
    tester,
  ) async {
    final f = await show(tester, 'reset');
    final fields = find.byType(TextField);
    final password = tester.widget<TextField>(fields.first).controller!;
    await tester.enterText(fields.first, 'FixturePass12');
    f.transport.handler = (m, p, b, t) async =>
        finalUser(permissions: ['USERS_VIEW']);
    await f.auth.checkSession();
    await tester.pump();
    expect(password.text, isEmpty);
    expect(find.byType(TextField), findsNothing);
    expect(find.text('ليست لديك صلاحية عرض هذه البيانات.'), findsOneWidget);
  });
  testWidgets(
    'network uncertainty never claims reset success or automatically retries',
    (tester) async {
      final f = await show(tester, 'reset');
      final fields = find.byType(TextField);
      final password = tester.widget<TextField>(fields.first).controller!;
      await tester.enterText(fields.at(0), 'FixturePass12');
      await tester.enterText(fields.at(1), 'FixturePass12');
      await reveal(
        tester,
        find.widgetWithText(FilledButton, 'إعادة تعيين كلمة المرور'),
      );
      await tester.tap(
        find.widgetWithText(FilledButton, 'إعادة تعيين كلمة المرور'),
      );
      await tester.pumpAndSettle();
      f.transport.handler = (m, p, b, t) async {
        if (m == 'POST') {
          throw const AppFailure('TIMEOUT', 'safe');
        }
        return finalResponse(p);
      };
      await tester.tap(find.text('تأكيد'));
      await tester.pumpAndSettle();
      expect(password.text, isEmpty);
      expect(find.textContaining('ربما تم حفظها'), findsOneWidget);
      expect(find.text('أكد الخادم حفظ العملية.'), findsNothing);
      expect(f.transport.calls.where((c) => c.method == 'POST').length, 1);
      expect(
        tester
            .widget<FilledButton>(
              find.widgetWithText(FilledButton, 'إعادة تعيين كلمة المرور'),
            )
            .onPressed,
        null,
      );
    },
  );
  testWidgets(
    'edit keeps immutable username read-only and exposes fixed role assignment',
    (tester) async {
      await show(tester, 'edit');
      final username = tester.widget<TextField>(find.byType(TextField).first);
      expect(username.readOnly, true);
      expect(username.controller!.text, 'user2');
      expect(find.text('الدور المعتمد'), findsOneWidget);
      expect(find.textContaining('حذف'), findsNothing);
    },
  );
  testWidgets(
    'uncertain create freezes identity until reread and explicit acknowledgement',
    (tester) async {
      final f = await show(tester, 'create');
      final fields = find.byType(TextField);
      final username = tester.widget<TextField>(fields.first).controller!;
      await tester.enterText(fields.at(0), 'new_user');
      await tester.enterText(fields.at(1), 'مستخدم جديد');
      await reveal(tester, find.byType(DropdownButtonFormField<String>));
      await tester.tap(find.byType(DropdownButtonFormField<String>));
      await tester.pumpAndSettle();
      await tester.tap(find.text('CASHIER').last);
      await tester.pumpAndSettle();
      await tester.enterText(fields.at(4), 'FixturePass12');
      await tester.enterText(fields.at(5), 'FixturePass12');
      await reveal(tester, find.widgetWithText(FilledButton, 'إنشاء مستخدم'));
      await tester.tap(find.widgetWithText(FilledButton, 'إنشاء مستخدم'));
      await tester.pumpAndSettle();
      f.transport.handler = (m, p, b, t) async {
        if (m == 'POST') throw const AppFailure('TIMEOUT', 'safe');
        return finalResponse(p);
      };
      await tester.tap(find.text('تأكيد'));
      await tester.pumpAndSettle();
      for (final field in tester.widgetList<TextField>(fields)) {
        expect(field.enabled, false);
      }
      expect(username.text, 'new_user');
      await reveal(tester, find.text('إعادة قراءة الحالة قبل المتابعة'));
      await tester.tap(find.text('إعادة قراءة الحالة قبل المتابعة'));
      await tester.pumpAndSettle();
      expect(
        find.textContaining('هذا لا يثبت عدم تنفيذ العملية'),
        findsOneWidget,
      );
      await reveal(tester, find.text('الإقرار قبل محاولة جديدة'));
      await tester.tap(find.text('الإقرار قبل محاولة جديدة'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('تأكيد'));
      await tester.pumpAndSettle();
      expect(tester.widget<TextField>(fields.first).enabled, true);
      expect(f.transport.calls.where((c) => c.method == 'POST').length, 1);
    },
  );
  testWidgets('audit unknown payload/action stays neutral and safe', (
    tester,
  ) async {
    final f = await show(tester, 'audit');
    f.transport.handler = (m, p, b, t) async => {
      'items': [
        auditEvent()
          ..['action'] = 'RAW_PASSWORD'
          ..['description'] = 'raw SQL secret',
      ],
      'page': 0,
      'size': 20,
      'totalItems': 1,
      'totalPages': 1,
    };
    await tester.tap(find.text('تحديث البيانات'));
    await tester.pumpAndSettle();
    await reveal(tester, find.text('حدث آخر'));
    expect(find.textContaining('raw SQL'), findsNothing);
    expect(find.textContaining('RAW_PASSWORD'), findsNothing);
  });
  testWidgets(
    'daily financially unauthorized response has no figures or accessible profit',
    (tester) async {
      await show(tester, 'daily', permissions: ['DASHBOARD']);
      expect(find.textContaining('9007199'), findsNothing);
      expect(find.textContaining('555.987'), findsNothing);
      expect(
        find.text('لا توجد أقسام متاحة لهذا الحساب في الاستجابة.'),
        findsOneWidget,
      );
    },
  );
}
