import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:manager_app/app/manager_app.dart';
import 'package:manager_app/core/errors/app_failure.dart';
import 'package:manager_app/core/theme/app_theme.dart';
import 'package:manager_app/features/profile/presentation/profile_screen.dart';
import 'package:manager_app/features/administration/data/admin_models.dart';
import 'package:manager_app/features/administration/presentation/admin_screen.dart';
import 'package:manager_app/features/quotations/presentation/quotation_screen.dart';
import 'package:manager_app/features/reports/presentation/report_screen.dart';
import 'package:manager_app/features/sales/presentation/sales_widgets.dart';
import 'package:manager_app/shared/widgets/states.dart';

import 'support/final_feature_fixtures.dart';
import 'support/fakes.dart';

void main() {
  testWidgets(
    'all drawer destinations remain reachable without stacked routes',
    (tester) async {
      final f = await finalAuth();
      addTearDown(f.auth.dispose);
      final original = f.transport.handler;
      f.transport.handler = (m, p, b, t) async => p == 'auth/sessions'
          ? [sessionJson(current: true)]
          : original(m, p, b, t);
      await tester.pumpWidget(ManagerApp(auth: f.auth, monitorSession: false));
      await tester.pumpAndSettle();
      for (var round = 0; round < 2; ++round) {
        for (final destination in [
          'لوحة المتابعة',
          'المنتجات والمخزون',
          'المبيعات والفواتير',
          'العملاء',
          'الموردون',
          'عروض الأسعار',
          'التقارير',
          'سجل العمليات',
          'إدارة المستخدمين',
          'الملف الشخصي والأجهزة',
        ]) {
          await tester.tap(find.byIcon(Icons.menu));
          await tester.pumpAndSettle();
          final label = find.descendant(
            of: find.byType(Drawer),
            matching: find.text(destination),
          );
          final scrollable = find.descendant(
            of: find.byType(Drawer),
            matching: find.byType(Scrollable),
          );
          await tester.drag(scrollable, const Offset(0, 1500));
          await tester.pumpAndSettle();
          await tester.scrollUntilVisible(label, 200, scrollable: scrollable);
          await tester.ensureVisible(label);
          await tester.pumpAndSettle();
          await tester.tap(label);
          await tester.pumpAndSettle();
          expect(tester.takeException(), isNull, reason: destination);
          final context = tester.element(find.byType(Scaffold).first);
          expect(Navigator.of(context).canPop(), isFalse, reason: destination);
        }
      }
      expect(f.transport.calls.where((c) => c.method != 'GET'), isEmpty);
    },
  );

  testWidgets('error announcement and retry remain accessible at 200 percent', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 700);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final semantics = tester.ensureSemantics();
    var retries = 0;
    await tester.pumpWidget(
      MaterialApp(
        theme: managerTheme(Brightness.light),
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(context)
              .copyWith(textScaler: TextScaler.linear(2)),
          child: child!,
        ),
        home: Scaffold(
          body: ErrorNotice(
            AppFailure.http(503, {'message': 'private-fixture'}),
            onRetry: () => ++retries,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.bySemanticsLabel(RegExp('private-fixture')), findsNothing);
    expect(
      tester
          .widgetList<Semantics>(find.byType(Semantics))
          .any((w) => w.properties.liveRegion == true),
      isTrue,
    );
    await expectLater(tester, meetsGuideline(androidTapTargetGuideline));
    await tester.tap(find.text('إعادة المحاولة'));
    expect(retries, 1);
    expect(tester.takeException(), isNull);
    expect(tester.getSize(find.byType(ErrorNotice)).height, lessThan(400));
    semantics.dispose();
  });
  testWidgets('late profile response cannot cross an account boundary', (
    tester,
  ) async {
    final f = await finalAuth();
    addTearDown(f.auth.dispose);
    final old = Completer<Object?>();
    var user = finalUser();
    var reads = 0;
    f.transport.handler = (_, path, _, _) async {
      if (path == 'auth/me') return user;
      if (path == 'auth/login') return {...loginJson(), 'user': user};
      if (path == 'auth/sessions') {
        if (++reads == 1) return old.future;
        return [
          {...sessionJson(current: true), 'deviceLabel': 'جهاز الحساب الحالي'},
        ];
      }
      return finalResponse(path);
    };
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(body: ProfileScreen(auth: f.auth)),
      ),
    );
    await tester.pump();
    await tester.pump();
    await f.auth.terminate();
    await tester.pump();
    user = {...finalUser(), 'id': 2, 'username': 'second-fixture'};
    await f.auth.login('second-fixture', 'synthetic-fixture');
    await tester.pumpAndSettle();
    old.complete([
      {...sessionJson(current: true), 'deviceLabel': 'جهاز الحساب السابق'},
    ]);
    await tester.pumpAndSettle();
    expect(find.text('جهاز الحساب السابق'), findsNothing);
    expect(find.text('جهاز الحساب الحالي'), findsOneWidget);
  });

  testWidgets('disposed profile ignores an outstanding read', (tester) async {
    final f = await finalAuth();
    addTearDown(f.auth.dispose);
    final pending = Completer<Object?>();
    f.transport.handler = (_, path, _, _) async =>
        path == 'auth/me' ? finalUser() : pending.future;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(body: ProfileScreen(auth: f.auth)),
      ),
    );
    await tester.pump();
    await tester.pump();
    await tester.pumpWidget(const SizedBox.shrink());
    pending.complete([sessionJson(current: true)]);
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
  });

  testWidgets('resume validates once and restarts the foreground monitor', (
    tester,
  ) async {
    final f = await finalAuth();
    addTearDown(f.auth.dispose);
    await tester.pumpWidget(ManagerApp(auth: f.auth));
    await tester.pumpAndSettle();
    f.transport.calls.clear();
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    await tester.pump(const Duration(seconds: 121));
    expect(f.transport.calls.where((c) => c.path == 'auth/me'), isEmpty);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pumpAndSettle();
    expect(f.transport.calls.where((c) => c.path == 'auth/me').length, 1);
    await tester.pump(const Duration(seconds: 61));
    await tester.pumpAndSettle();
    expect(f.transport.calls.where((c) => c.path == 'auth/me').length, 2);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('logout removes a pushed protected route', (tester) async {
    final f = await finalAuth();
    addTearDown(f.auth.dispose);
    await tester.pumpWidget(ManagerApp(auth: f.auth, monitorSession: false));
    await tester.pumpAndSettle();
    final context = tester.element(find.byType(Scaffold).first);
    unawaited(
      Navigator.of(context).push(
        MaterialPageRoute<void>(
          builder: (_) => const Scaffold(body: Text('protected-route-fixture')),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await f.auth.terminate();
    await tester.pumpAndSettle();
    expect(find.text('protected-route-fixture'), findsNothing);
    expect(find.byType(ProfileScreen), findsNothing);
  });

  for (final screen in ['user', 'quotation', 'daily', 'profile']) {
    testWidgets('$screen isolates dates inside Arabic labels', (tester) async {
      final f = await finalAuth();
      addTearDown(f.auth.dispose);
      final original = f.transport.handler;
      f.transport.handler = (m, p, b, t) async => p == 'auth/sessions'
          ? [sessionJson(current: true)]
          : original(m, p, b, t);
      final metadata = AdminMetadata(
        adminRoles().map(AdminRole.fromJson).toList(),
        adminPermissions().map(AdminPermission.fromJson).toList(),
      );
      final child = switch (screen) {
        'user' => AdminDetailScreen(auth: f.auth, id: 2, metadata: metadata),
        'quotation' => QuotationDetailScreen(auth: f.auth, id: 1),
        'daily' => ReportReadScreen(auth: f.auth, kind: ReportKind.daily),
        _ => ProfileScreen(auth: f.auth),
      };
      await tester.pumpWidget(
        MaterialApp(
          builder: (_, child) =>
              Directionality(textDirection: TextDirection.rtl, child: child!),
          home: Scaffold(body: child),
        ),
      );
      await tester.pumpAndSettle();
      final labels = tester
          .widgetList<Text>(find.byType(Text, skipOffstage: false))
          .map((w) => w.data ?? '')
          .where((s) => s.contains('\u2066'))
          .toList();
      expect(labels, isNotEmpty);
      expect(labels.every((s) => s.contains('\u2069')), isTrue);
      expect(
        isolateDate('2026-10-07T12:00:00'),
        '\u20662026-10-07T12:00:00\u2069',
      );
      expect(tester.takeException(), isNull);
    });
  }
  testWidgets('profile concurrent refresh must share one session request', (
    tester,
  ) async {
    final f = await finalAuth();
    addTearDown(f.auth.dispose);
    final response = Completer<Object?>();
    f.transport.handler = (_, p, _, _) async => p == 'auth/me'
        ? finalUser()
        : p == 'auth/sessions'
        ? response.future
        : finalResponse(p);
    await tester.pumpWidget(
      MaterialApp(
        theme: managerTheme(Brightness.light),
        home: Scaffold(body: ProfileScreen(auth: f.auth)),
      ),
    );
    await tester.pump();
    await tester.pump();
    final refresh = tester.widget<RefreshIndicator>(
      find.byType(RefreshIndicator),
    );
    final first = refresh.onRefresh(), second = refresh.onRefresh();
    await tester.pump();
    await tester.pump();
    final requests = f.transport.calls
        .where((c) => c.path == 'auth/sessions')
        .length;
    response.complete([sessionJson(current: true)]);
    await Future.wait([first, second]);
    await tester.pumpAndSettle();
    expect(
      requests,
      1,
      reason: 'initial load and concurrent refresh must share one request',
    );
  });
  testWidgets('background monitoring must not issue requests while paused', (
    tester,
  ) async {
    final f = await finalAuth();
    addTearDown(f.auth.dispose);
    await tester.pumpWidget(ManagerApp(auth: f.auth));
    await tester.pumpAndSettle();
    f.transport.calls.clear();
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    await tester.pump(const Duration(seconds: 61));
    final calls = f.transport.calls.where((c) => c.path == 'auth/me').length;
    await tester.pumpWidget(const SizedBox.shrink());
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    expect(calls, 0);
  });
  testWidgets('narrow scaled error retains readable message beside retry', (
    tester,
  ) async {
    await tester.binding.setSurfaceSize(const Size(320, 700));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(
      MaterialApp(
        theme: managerTheme(Brightness.light),
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(context)
              .copyWith(textScaler: TextScaler.linear(2)),
          child: Directionality(
            textDirection: TextDirection.rtl,
            child: child!,
          ),
        ),
        home: Scaffold(
          body: Padding(
            padding: const EdgeInsets.all(24),
            child: ErrorNotice(
              const AppFailure(
                'NETWORK',
                'تعذر الاتصال بالخدمة. تحقق من الشبكة وأعد المحاولة.',
              ),
              onRetry: () {},
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(
      tester
          .getSize(
            find.text('تعذر الاتصال بالخدمة. تحقق من الشبكة وأعد المحاولة.'),
          )
          .width,
      greaterThanOrEqualTo(100),
    );
    expect(tester.takeException(), isNull);
  });
}
