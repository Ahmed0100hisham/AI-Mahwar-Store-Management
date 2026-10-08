import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:manager_app/app/manager_app.dart';
import 'package:manager_app/core/errors/app_failure.dart';
import 'package:manager_app/features/auth/data/auth_repository.dart';
import 'package:manager_app/features/auth/state/auth_controller.dart';

import 'support/fakes.dart';

void main() {
  late AuthController auth;
  late FakeTransport transport;
  setUp(() {
    transport = FakeTransport();
    auth = AuthController(AuthRepository(transport), MemoryVault());
    transport.handler = (_, path, _, _) async => switch (path) {
      'auth/login' => loginJson(),
      'auth/me' => userJson(),
      'auth/sessions' => [sessionJson(current: true)],
      _ => null,
    };
  });
  tearDown(() => auth.dispose());
  Future<void> show(WidgetTester tester) async {
    await auth.restore();
    await tester.pumpWidget(ManagerApp(auth: auth, monitorSession: false));
    await tester.pumpAndSettle();
  }

  testWidgets('Arabic login is RTL and validates before network', (
    tester,
  ) async {
    await show(tester);
    expect(
      Directionality.of(tester.element(find.text('مرحباً بك'))),
      TextDirection.rtl,
    );
    await tester.tap(find.text('تسجيل الدخول'));
    await tester.pumpAndSettle();
    expect(find.text('أدخل اسم المستخدم.'), findsOneWidget);
    expect(find.text('أدخل كلمة المرور.'), findsOneWidget);
    expect(transport.calls, isEmpty);
  });
  testWidgets('password visibility toggles and keyboard login opens profile', (
    tester,
  ) async {
    await show(tester);
    await tester.enterText(find.byType(TextFormField).at(0), 'fixture');
    await tester.enterText(
      find.byType(TextFormField).at(1),
      'fixture-password',
    );
    await tester.tap(find.byTooltip('إظهار كلمة المرور'));
    await tester.pump();
    expect(find.byTooltip('إخفاء كلمة المرور'), findsOneWidget);
    await tester.testTextInput.receiveAction(TextInputAction.done);
    await tester.pumpAndSettle();
    expect(find.text('الملف الشخصي والأجهزة'), findsWidgets);
    expect(find.text('هذا الجهاز'), findsOneWidget);
  });
  testWidgets('restricted login cannot enter Manager shell', (tester) async {
    transport.handler = (_, path, _, _) async => path == 'auth/login'
        ? loginJson(restricted: true)
        : userJson(restricted: true);
    await show(tester);
    await tester.enterText(find.byType(TextFormField).at(0), 'fixture');
    await tester.enterText(
      find.byType(TextFormField).at(1),
      'fixture-password',
    );
    await tester.tap(find.text('تسجيل الدخول'));
    await tester.pumpAndSettle();
    expect(find.text('خطوة ضرورية لحماية حسابك'), findsOneWidget);
    expect(find.text('الملف الشخصي والأجهزة'), findsNothing);
    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(find.text('خطوة ضرورية لحماية حسابك'), findsOneWidget);
  });
  testWidgets('terminal unauthorized validation redirects to login', (
    tester,
  ) async {
    await show(tester);
    await auth.login('fixture', 'fixture-password');
    await tester.pumpAndSettle();
    transport.handler = (_, _, _, _) async => throw AppFailure.signedOut;
    await auth.checkSession();
    await tester.pumpAndSettle();
    expect(find.text('مرحباً بك'), findsOneWidget);
  });
  testWidgets('small screen and enlarged text do not overflow', (tester) async {
    tester.view.physicalSize = const Size(360, 740);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    tester.platformDispatcher.textScaleFactorTestValue = 1.4;
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    await show(tester);
    expect(tester.takeException(), isNull);
  });
}
