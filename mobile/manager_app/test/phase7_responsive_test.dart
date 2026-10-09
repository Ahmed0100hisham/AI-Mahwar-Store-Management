import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:manager_app/core/theme/app_theme.dart';
import 'package:manager_app/features/auth/state/auth_controller.dart';
import 'package:manager_app/features/auth/presentation/login_screen.dart';
import 'package:manager_app/features/auth/presentation/change_password_screen.dart';
import 'package:manager_app/features/profile/presentation/profile_screen.dart';
import 'package:manager_app/features/dashboard/presentation/dashboard_screen.dart';
import 'package:manager_app/features/inventory/presentation/inventory_screen.dart';
import 'package:manager_app/features/inventory/presentation/product_detail_screen.dart';
import 'package:manager_app/features/inventory/presentation/movement_screen.dart';
import 'package:manager_app/features/sales/presentation/sales_screen.dart';
import 'package:manager_app/features/sales/presentation/invoice_detail_screen.dart';
import 'package:manager_app/features/parties/data/party_access.dart';
import 'package:manager_app/features/parties/presentation/party_screen.dart';
import 'package:manager_app/features/parties/presentation/party_detail_screen.dart';
import 'package:manager_app/features/parties/presentation/party_account_screen.dart';
import 'package:manager_app/features/quotations/presentation/quotation_screen.dart';
import 'package:manager_app/features/reports/presentation/report_screen.dart';
import 'package:manager_app/features/audit/presentation/audit_screen.dart';
import 'package:manager_app/features/administration/data/admin_models.dart';
import 'package:manager_app/features/administration/presentation/admin_screen.dart';
import 'package:manager_app/features/administration/presentation/admin_editor.dart';
import 'package:manager_app/features/administration/presentation/admin_confirmation.dart';
import 'package:manager_app/shared/widgets/states.dart';

import 'support/final_feature_fixtures.dart';
import 'support/fakes.dart';

const readinessScreens = [
  'login',
  'password',
  'profile',
  'dashboard',
  'inventory',
  'low-stock',
  'product',
  'movements',
  'sales',
  'invoices',
  'invoice',
  'customers',
  'customer',
  'customer-account',
  'suppliers',
  'supplier',
  'supplier-account',
  'quotations',
  'quotation',
  'reports',
  'expenses',
  'cash',
  'daily',
  'inventory-summary',
  'slow',
  'audit',
  'users',
  'user',
  'create',
  'edit',
  'reset',
];

Widget readinessScreen(String name, AuthController auth) {
  final metadata = AdminMetadata(
    adminRoles().map(AdminRole.fromJson).toList(),
    adminPermissions().map(AdminPermission.fromJson).toList(),
  );
  return switch (name) {
    'login' => LoginScreen(auth: auth),
    'password' => ChangePasswordScreen(auth: auth),
    'profile' => ProfileScreen(auth: auth),
    'dashboard' => DashboardScreen(auth: auth),
    'inventory' => InventoryScreen(auth: auth),
    'low-stock' => InventoryScreen(auth: auth, lowStock: true),
    'product' => ProductDetailScreen(auth: auth, id: 1),
    'movements' => MovementScreen(auth: auth, productId: 1, unit: 'قطعة'),
    'sales' => SalesScreen(auth: auth),
    'invoices' => SalesScreen(auth: auth, invoices: true),
    'invoice' => InvoiceDetailScreen(auth: auth, id: 1),
    'customers' => PartyScreen(auth: auth, kind: PartyKind.customer),
    'customer' => PartyDetailScreen(
      auth: auth,
      kind: PartyKind.customer,
      id: 1,
    ),
    'customer-account' => PartyAccountScreen(
      auth: auth,
      kind: PartyKind.customer,
      id: 1,
    ),
    'suppliers' => PartyScreen(auth: auth, kind: PartyKind.supplier),
    'supplier' => PartyDetailScreen(
      auth: auth,
      kind: PartyKind.supplier,
      id: 1,
    ),
    'supplier-account' => PartyAccountScreen(
      auth: auth,
      kind: PartyKind.supplier,
      id: 1,
    ),
    'quotations' => QuotationScreen(auth: auth),
    'quotation' => QuotationDetailScreen(auth: auth, id: 1),
    'reports' => ReportScreen(auth: auth),
    'expenses' => ReportReadScreen(auth: auth, kind: ReportKind.expenses),
    'cash' => ReportReadScreen(auth: auth, kind: ReportKind.cash),
    'daily' => ReportReadScreen(auth: auth, kind: ReportKind.daily),
    'inventory-summary' => ReportReadScreen(
      auth: auth,
      kind: ReportKind.inventory,
    ),
    'slow' => SlowReportScreen(auth: auth),
    'audit' => AuditScreen(auth: auth),
    'users' => AdminScreen(auth: auth),
    'user' => AdminDetailScreen(auth: auth, id: 2, metadata: metadata),
    'create' => AdminEditor(auth: auth, metadata: metadata),
    'edit' => AdminEditor(
      auth: auth,
      metadata: metadata,
      target: AdminUser.fromJson(adminUser()),
    ),
    'reset' => AdminEditor(
      auth: auth,
      metadata: metadata,
      target: AdminUser.fromJson(adminUser()),
      reset: true,
    ),
    _ => throw ArgumentError.value(name),
  };
}

Widget readinessApp(Widget child, Brightness brightness, double scale) =>
    MaterialApp(
      locale: const Locale('ar'),
      supportedLocales: const [Locale('ar'), Locale('en')],
      localizationsDelegates: GlobalMaterialLocalizations.delegates,
      theme: managerTheme(brightness),
      builder: (context, child) => MediaQuery(
        data: MediaQuery.of(context)
            .copyWith(textScaler: TextScaler.linear(scale)),
        child: child!,
      ),
      home: Scaffold(body: child),
    );

void main() {
  for (final admin in [true, false]) {
    testWidgets(
      'long ${admin ? 'admin' : 'session'} confirmation remains readable at 320px and 200 percent',
      (tester) async {
        tester.view.physicalSize = const Size(320, 700);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        final f = await finalAuth();
        addTearDown(f.auth.dispose);
        final message =
            '${List.filled(9, 'اسم مستخدم').join(' ')}\n${List.filled(50, 'u').join()}\nسيؤكد الخادم إلغاء الجلسات القديمة؛ لا تعود الجلسات عند التفعيل.';
        bool? confirmed;
        await tester.pumpWidget(
          readinessApp(
            Builder(
              builder: (context) => TextButton(
                onPressed: () async => confirmed = await (admin
                    ? confirmAdminAction(
                        context,
                        f.auth,
                        'إعادة تعيين كلمة المرور',
                        message,
                      )
                    : confirmAction(
                        context,
                        'إعادة تعيين كلمة المرور',
                        message,
                      )),
                child: const Text('افتح التأكيد'),
              ),
            ),
            Brightness.light,
            2,
          ),
        );
        await tester.tap(find.text('افتح التأكيد'));
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);
        final paragraph = tester.renderObject<RenderParagraph>(
          find.text(message),
        );
        final end = paragraph.getBoxesForSelection(
          TextSelection(
            baseOffset: message.length - 6,
            extentOffset: message.length,
          ),
        );
        expect(
          end.last.bottom,
          lessThanOrEqualTo(paragraph.size.height),
          reason:
              'the final warning line must not be clipped out of the paragraph',
        );
        final scrollable = find.descendant(
          of: find.byType(AlertDialog),
          matching: find.byType(Scrollable),
        );
        expect(
          scrollable,
          findsOneWidget,
          reason: 'the complete identity and warning must be readable without clipping',
        );
        await tester.drag(scrollable, const Offset(0, -500));
        await tester.pumpAndSettle();
        await tester.tap(find.text('إلغاء'));
        await tester.pumpAndSettle();
        expect(confirmed, isFalse);
        expect(f.transport.calls, isEmpty);
      },
    );
  }
  for (final size in [
    const Size(320, 700),
    const Size(390, 844),
    const Size(800, 1024),
  ]) {
    for (final scale in [1.0, 1.5, 2.0]) {
      for (final brightness in Brightness.values) {
        testWidgets(
          'Arabic screens ${size.width}x${size.height} scale $scale $brightness',
          (tester) async {
            tester.view.physicalSize = size;
            tester.view.devicePixelRatio = 1;
            addTearDown(tester.view.resetPhysicalSize);
            addTearDown(tester.view.resetDevicePixelRatio);
            for (final name in readinessScreens) {
              final f = await finalAuth();
              final original = f.transport.handler;
              f.transport.handler = (m, p, b, t) async => p == 'auth/sessions'
                  ? [sessionJson(current: true)]
                  : original(m, p, b, t);
              await tester.pumpWidget(
                readinessApp(readinessScreen(name, f.auth), brightness, scale),
              );
              await tester.pumpAndSettle();
              expect(
                tester.takeException(),
                isNull,
                reason: '$name initial layout',
              );
              if (find.byType(Scrollable).evaluate().isNotEmpty) {
                for (var i = 0; i < 3; ++i) {
                  await tester.drag(
                    find.byType(Scrollable).first,
                    const Offset(0, -400),
                  );
                  await tester.pumpAndSettle();
                  expect(
                    tester.takeException(),
                    isNull,
                    reason: '$name scroll $i',
                  );
                }
              }
              expect(
                f.transport.calls.where((c) => c.method != 'GET'),
                isEmpty,
                reason: '$name rendering must not submit a write',
              );
              await tester.pumpWidget(const SizedBox.shrink());
              f.auth.dispose();
            }
          },
        );
      }
    }
  }
  for (final name in ['login', 'password', 'create', 'reset']) {
    testWidgets('$name avoids keyboard at 320px and 200 percent text', (
      tester,
    ) async {
      tester.view.physicalSize = const Size(320, 700);
      tester.view.devicePixelRatio = 1;
      tester.view.viewInsets = const FakeViewPadding(bottom: 300);
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.view.resetViewInsets);
      final f = await finalAuth();
      addTearDown(f.auth.dispose);
      await tester.pumpWidget(
        readinessApp(readinessScreen(name, f.auth), Brightness.light, 2),
      );
      await tester.pumpAndSettle();
      for (
        var i = 0;
        find.byType(TextField).evaluate().isEmpty && i < 10;
        ++i
      ) {
        await tester.drag(find.byType(Scrollable).first, const Offset(0, -300));
        await tester.pumpAndSettle();
      }
      await tester.ensureVisible(find.byType(TextField).first);
      await tester.pumpAndSettle();
      await tester.tap(find.byType(TextField).first);
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(tester.testTextInput.isVisible, isTrue);
      final fields = tester.widgetList<TextField>(find.byType(TextField));
      for (final field in fields.where(
        (f) => f.obscureText && (name == 'login' || name == 'password'),
      )) {
        expect(
          field.decoration?.suffixIcon,
          isNotNull,
          reason: 'obscured password retains its visibility control',
        );
      }
    });
  }
}
