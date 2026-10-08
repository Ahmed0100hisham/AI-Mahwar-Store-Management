// Explicit opt-in: flutter test test/live_dashboard_contract.dart --no-pub
// Only an externally provisioned disposable fixture may supply this environment.
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:manager_app/core/config/app_config.dart';
import 'package:manager_app/core/errors/app_failure.dart';
import 'package:manager_app/core/network/api_transport.dart';
import 'package:manager_app/features/auth/data/auth_repository.dart';
import 'package:manager_app/features/auth/state/auth_controller.dart';
import 'package:manager_app/features/dashboard/data/dashboard_access.dart';
import 'package:manager_app/features/dashboard/data/dashboard_models.dart';
import 'package:manager_app/features/dashboard/data/dashboard_repository.dart';
import 'package:manager_app/features/dashboard/state/dashboard_controller.dart';

import 'support/fakes.dart';

class ObservedTransport implements ApiTransport {
  ObservedTransport(this.inner);
  final ApiTransport inner;
  final cacheControls = <String?>[];
  final calls = <({String method, String path, Object? response})>[];
  @override
  Future<Object?> send(
    String method,
    String path, {
    Object? body,
    String? accessToken,
  }) async {
    final response = await inner.send(
      method,
      path,
      body: body,
      accessToken: accessToken,
    );
    // Business responses remain in test memory only; never retain auth responses.
    if (path.startsWith('manager/')) {
      calls.add((method: method, path: path, response: response));
    }
    return response;
  }
}

void main() {
  final env = Platform.environment;
  final url = env['MANAGER_TEST_API_URL'],
      password = env['MANAGER_TEST_PASSWORD'];
  if (url == null ||
      password == null ||
      env['MANAGER_TEST_DISPOSABLE'] != 'true') {
    throw StateError('An explicitly disposable API fixture is required.');
  }
  ({AuthController auth, ObservedTransport transport}) create() {
    final dio = Dio();
    final transport = ObservedTransport(
      DioApiTransport(
        AppConfig(url, allowHttp: true, release: false),
        dio: dio,
      ),
    );
    dio.interceptors.add(
      InterceptorsWrapper(
        onResponse: (response, handler) {
          if (response.requestOptions.path.startsWith('manager/')) {
            transport.cacheControls.add(
              response.headers.value('cache-control'),
            );
          }
          handler.next(response);
        },
      ),
    );
    return (
      auth: AuthController(AuthRepository(transport), MemoryVault()),
      transport: transport,
    );
  }

  for (final role in ['ADMIN', 'ACCOUNTANT', 'CASHIER', 'STOREKEEPER']) {
    test(
      'live $role dashboard uses frozen GETs and permission redactions',
      () async {
        final fixture = create();
        // Keep assertions boolean when they concern credentials/identity.
        final current = fixture.auth;
        try {
          expect(
            await current.login('p3_$role', password),
            isTrue,
            reason: current.error?.code,
          );
          final access = DashboardAccess(current.user!);
          final repository = DashboardRepository(current.repository.client);
          for (final period in DashboardPeriod.values) {
            final data = await repository.load(access, period);
            expect(
              data.range.from.iso,
              period.range(data.overview.businessDate).from.iso,
            );
            expect(
              data.range.to.iso,
              period.range(data.overview.businessDate).to.iso,
            );
            expect(data.daily.date.iso, data.overview.businessDate.iso);
            expect(data.sales != null, access.sales);
            expect(data.cash != null, access.cash);
            expect(data.expenses != null, access.expenses);
            expect(data.daily.inventory != null, access.inventory);
            if (access.sales) {
              expect(data.sales!.netSales, matches(RegExp(r'^-?\d+\.\d{3}$')));
              expect(data.sales!.netProfit != null, access.profit);
              expect(
                data.top!.every((p) => access.profit || p.profit == null),
                isTrue,
              );
            }
            if (role == 'ADMIN' &&
                period == DashboardPeriod.thisMonth &&
                data.range.from.iso == '2026-10-01') {
              // Independent values from the disposable released-schema fixture.
              expect(data.sales!.grossSales, '1019.000');
              expect(data.sales!.returns, '5.625');
              expect(data.sales!.netSales, '1013.375');
              expect(data.sales!.netProfit, '1002.750');
              expect(data.cash!.opening, '50.000');
              expect(data.cash!.closing, '63.875');
            }
            if (access.inventory) {
              expect(data.daily.inventory!.active, 3);
              expect(
                data.daily.inventory!.valueAtCost,
                access.cost ? '41.248' : null,
              );
              expect(data.lowStock!.total, 2);
              expect(
                data.lowStock!.items.any((p) => p.quantity == '2.125'),
                isTrue,
              );
              expect(data.slow!.items.any((p) => p.code == 'P3-C'), isTrue);
            }
            if (!access.metric('RECEIVABLES')) {
              expect(data.overview.metrics.containsKey('RECEIVABLES'), isFalse);
            }
            if (!access.metric('PAYABLES')) {
              expect(data.overview.metrics.containsKey('PAYABLES'), isFalse);
            }
          }
          expect(
            fixture.transport.calls.every((c) => c.method == 'GET'),
            isTrue,
          );
          if (role == 'ADMIN') {
            expect(
              fixture.transport.cacheControls.length,
              fixture.transport.calls.length,
            );
            expect(
              fixture.transport.cacheControls.every(
                (value) => value?.contains('no-store') == true,
              ),
              isTrue,
            );
            expect(
              fixture.transport.calls
                  .map((c) => Uri.parse(c.path).path)
                  .toSet()
                  .length,
              9,
            );
          }
          if (role == 'CASHIER') {
            final daily =
                fixture.transport.calls
                        .firstWhere(
                          (c) => c.path.startsWith('manager/daily-summary'),
                        )
                        .response
                    as Map;
            expect((daily['sales'] as Map).containsKey('profit'), isFalse);
            expect(daily.containsKey('inventorySnapshot'), isFalse);
            expect(daily.containsKey('expenses'), isFalse);
            expect(daily.containsKey('cashbox'), isFalse);
          }
          if (role == 'STOREKEEPER') {
            expect(
              fixture.transport.calls.any(
                (c) => c.path.startsWith('manager/sales/summary'),
              ),
              isFalse,
            );
          }
        } finally {
          await current.logout();
          current.dispose();
        }
      },
      timeout: const Timeout(Duration(minutes: 2)),
    );
  }
  test('live restricted session cannot request dashboard', () async {
    final fixture = create();
    final auth = fixture.auth;
    DashboardController? dashboard;
    try {
      expect(await auth.login('flutter_restricted', password), isTrue);
      expect(auth.status, AuthStatus.restricted);
      dashboard = DashboardController(
        auth,
        DashboardRepository(auth.repository.client),
      );
      await dashboard.load();
      expect(dashboard.data, isNull);
      expect(fixture.transport.calls, isEmpty);
      await expectLater(
        auth.repository.client.send('GET', 'manager/dashboard'),
        throwsA(isA<AppFailure>()),
      );
    } finally {
      dashboard?.dispose();
      await auth.logout();
      auth.dispose();
    }
  });
  test(
    'live revoked dashboard session clears cached figures centrally',
    () async {
      final fixture = create(), second = create();
      final auth = fixture.auth;
      DashboardController? dashboard;
      try {
        expect(await auth.login('p3_ADMIN', password), isTrue);
        expect(await second.auth.login('p3_ADMIN', password), isTrue);
        dashboard = DashboardController(
          auth,
          DashboardRepository(auth.repository.client),
        );
        await dashboard.load();
        expect(dashboard.data != null, isTrue);
        await second.auth.repository.revoke(auth.sid!);
        await dashboard.load();
        expect(auth.status, AuthStatus.signedOut);
        expect(dashboard.data, isNull);
      } finally {
        dashboard?.dispose();
        await auth.logout();
        await second.auth.logout();
        auth.dispose();
        second.auth.dispose();
      }
    },
  );
}
