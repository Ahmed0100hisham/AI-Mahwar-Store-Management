import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:manager_app/core/errors/app_failure.dart';
import 'package:manager_app/features/auth/state/auth_controller.dart';
import 'package:manager_app/features/dashboard/data/dashboard_models.dart';
import 'package:manager_app/features/dashboard/data/dashboard_repository.dart';
import 'package:manager_app/features/dashboard/state/dashboard_controller.dart';

import 'support/dashboard_fixtures.dart';
import 'support/fakes.dart';

void main() {
  Future<
    ({DashboardController state, AuthController auth, FakeTransport transport})
  >
  setup({
    List<String> permissions = dashboardPermissions,
    bool restricted = false,
  }) async {
    final fixture = await dashboardAuth(
      permissions: permissions,
      restricted: restricted,
    );
    final state = DashboardController(
      fixture.auth,
      DashboardRepository(fixture.auth.repository.client),
    );
    addTearDown(() {
      state.dispose();
      fixture.auth.dispose();
    });
    return (state: state, auth: fixture.auth, transport: fixture.transport);
  }

  test(
    'initial loading publishes no fake numbers and completes with success',
    () async {
      final fixture = await setup(), gate = Completer<Object?>();
      fixture.transport.handler = (_, path, _, _) => path == 'manager/dashboard'
          ? gate.future
          : Future.value(dashboardResponse(path));
      final request = fixture.state.load();
      expect(fixture.state.loading, isTrue);
      expect(fixture.state.data, isNull);
      gate.complete(fixtureOverview());
      await request;
      expect(fixture.state.loading, isFalse);
      expect(fixture.state.error, isNull);
      expect(fixture.state.data!.sales!.netSales, '9007199254740993.125');
    },
  );
  test('refresh retains previous successful data while pending', () async {
    final fixture = await setup();
    await fixture.state.load();
    final previous = fixture.state.data, gate = Completer<Object?>();
    fixture.transport.handler = (_, path, _, _) => path == 'manager/dashboard'
        ? gate.future
        : Future.value(dashboardResponse(path));
    final request = fixture.state.load();
    expect(fixture.state.data, same(previous));
    expect(fixture.state.loading, isTrue);
    gate.complete(fixtureOverview());
    await request;
    expect(fixture.state.loading, isFalse);
    expect(fixture.state.error, isNull);
  });
  test(
    'refresh network failure keeps prior data and explicit retry succeeds',
    () async {
      final fixture = await setup();
      await fixture.state.load();
      final previous = fixture.state.data;
      fixture.transport.handler = (_, _, _, _) async =>
          throw const AppFailure('CONNECTION', 'تعذر الاتصال بالخدمة.');
      await fixture.state.load();
      expect(fixture.state.data, same(previous));
      expect(fixture.state.error!.code, 'CONNECTION');
      fixture.transport.handler = (_, path, _, _) async =>
          dashboardResponse(path);
      await fixture.state.load();
      expect(fixture.state.error, isNull);
    },
  );
  test('initial failure has no data and explicit retry loads', () async {
    final fixture = await setup();
    fixture.transport.handler = (_, _, _, _) async =>
        throw AppFailure.http(503, null);
    await fixture.state.load();
    expect(fixture.state.data, isNull);
    expect(fixture.state.error!.status, 503);
    fixture.transport.handler = (_, path, _, _) async =>
        dashboardResponse(path);
    await fixture.state.load();
    expect(fixture.state.data, isNotNull);
  });
  test('concurrent refreshes join one request batch', () async {
    final fixture = await setup(), gate = Completer<Object?>();
    fixture.transport.handler = (_, path, _, _) => path == 'manager/dashboard'
        ? gate.future
        : Future.value(dashboardResponse(path));
    final first = fixture.state.load(),
        second = fixture.state.load(),
        third = fixture.state.load();
    expect(identical(first, second) && identical(first, third), isTrue);
    expect(
      fixture.transport.calls
          .where((c) => c.path == 'manager/dashboard')
          .length,
      1,
    );
    gate.complete(fixtureOverview());
    await Future.wait([first, second, third]);
  });
  test('late old period response cannot replace newer state', () async {
    final fixture = await setup(), old = Completer<Object?>();
    var overviewCalls = 0;
    fixture.transport.handler = (_, path, _, _) {
      if (path == 'manager/dashboard' && overviewCalls++ == 0) {
        return old.future;
      }
      return Future.value(dashboardResponse(path));
    };
    final pending = fixture.state.load();
    await fixture.state.selectPeriod(DashboardPeriod.thisWeek);
    expect(fixture.state.data!.period, DashboardPeriod.thisWeek);
    old.complete(fixtureOverview());
    await pending;
    expect(fixture.state.data!.period, DashboardPeriod.thisWeek);
    expect(fixture.state.data!.range.from.iso, '2026-10-04');
  });
  test('permission downgrade removes old private data immediately', () async {
    final fixture = await setup();
    await fixture.state.load();
    expect(fixture.state.data!.sales!.netProfit, isNotNull);
    final gate = Completer<Object?>();
    fixture.transport.handler = (_, path, _, _) => path == 'auth/me'
        ? Future.value(userJson(permissions: ['DASHBOARD', 'SALES_VIEW']))
        : path == 'manager/dashboard'
        ? gate.future
        : Future.value(dashboardResponse(path));
    await fixture.auth.revalidate();
    expect(fixture.state.data, isNull);
    expect(fixture.state.loading, isTrue);
    gate.complete(fixtureOverview());
    await fixture.state.load();
    expect(fixture.state.data!.sales, isNull);
    expect(
      fixture.state.data!.overview.metrics.containsKey('NET_PROFIT'),
      isFalse,
    );
  });
  test(
    'late privileged request cannot republish after permission downgrade',
    () async {
      final fixture = await setup(), old = Completer<Object?>();
      var overviewCalls = 0;
      fixture.transport.handler = (_, path, _, _) {
        if (path == 'auth/me') {
          return Future.value(
            userJson(permissions: ['DASHBOARD', 'SALES_VIEW']),
          );
        }
        if (path == 'manager/dashboard' && overviewCalls++ == 0) {
          return old.future;
        }
        return Future.value(dashboardResponse(path));
      };
      final previous = fixture.state.load();
      await fixture.auth.revalidate();
      await fixture.state.load();
      old.complete(fixtureOverview());
      await previous;
      expect(fixture.state.data!.sales, isNull);
      expect(
        fixture.state.data!.overview.metrics.containsKey('RECEIVABLES'),
        isFalse,
      );
    },
  );
  test(
    'authorization rejection clears prior data instead of retaining it',
    () async {
      final fixture = await setup();
      await fixture.state.load();
      fixture.transport.handler = (_, path, _, _) async => path == 'auth/me'
          ? userJson(permissions: dashboardPermissions)
          : throw AppFailure.http(403, null);
      await fixture.state.load();
      expect(fixture.state.data, isNull);
      expect(fixture.state.error!.status, 403);
    },
  );
  test(
    'terminal dashboard authentication failure uses centralized sign-out',
    () async {
      final fixture = await setup();
      await fixture.state.load();
      fixture.transport.handler = (_, _, _, _) async =>
          throw AppFailure.signedOut;
      await fixture.state.load();
      expect(fixture.auth.status, AuthStatus.signedOut);
      expect(fixture.state.data, isNull);
      expect(
        fixture.transport.calls.where((c) => c.path == 'auth/refresh').length,
        1,
      );
    },
  );
  test(
    '403 notifies cleared figures before slow me validation completes',
    () async {
      final fixture = await setup(), gate = Completer<Object?>();
      await fixture.state.load();
      var sawCleared = false;
      fixture.state.addListener(() {
        if (fixture.state.error?.status == 403 && fixture.state.data == null) {
          sawCleared = true;
        }
      });
      fixture.transport.handler = (_, path, _, _) => path == 'auth/me'
          ? gate.future
          : Future.error(AppFailure.http(403, null));
      final pending = fixture.state.load();
      await Future<void>.delayed(Duration.zero);
      expect(sawCleared, isTrue);
      expect(fixture.state.data, isNull);
      gate.complete(userJson(permissions: dashboardPermissions));
      await pending;
    },
  );
  test(
    'dashboard 401 recovers through Phase 1 refresh and retry once',
    () async {
      final fixture = await setup();
      var attempts = 0;
      fixture.transport.handler = (_, path, _, _) async {
        if (path == 'manager/dashboard' && attempts++ == 0) {
          throw AppFailure.signedOut;
        }
        if (path == 'auth/refresh') {
          return {
            ...loginJson(access: 'rotated-fixture'),
            'user': userJson(permissions: dashboardPermissions),
          };
        }
        return dashboardResponse(path);
      };
      await fixture.state.load();
      expect(fixture.state.data, isNotNull);
      expect(attempts, 2);
      expect(
        fixture.transport.calls.where((c) => c.path == 'auth/refresh').length,
        1,
      );
    },
  );
  test('must-change session never requests Manager data', () async {
    final fixture = await setup(restricted: true);
    await fixture.state.load();
    expect(fixture.state.allowed, isFalse);
    expect(fixture.state.data, isNull);
    expect(
      fixture.transport.calls.where((c) => c.path.startsWith('manager/')),
      isEmpty,
    );
  });
  test('missing DASHBOARD permission prevents all Manager requests', () async {
    final fixture = await setup(permissions: ['REPORTS_VIEW', 'REPORTS_SALES']);
    await fixture.state.load();
    expect(fixture.state.error!.status, 403);
    expect(
      fixture.transport.calls.where((c) => c.path.startsWith('manager/')),
      isEmpty,
    );
  });
  test('logout clears state and late response cannot restore it', () async {
    final fixture = await setup(), gate = Completer<Object?>();
    fixture.transport.handler = (_, path, _, _) => path == 'manager/dashboard'
        ? gate.future
        : Future.value(dashboardResponse(path));
    final pending = fixture.state.load();
    await fixture.auth.terminate();
    gate.complete(fixtureOverview());
    await pending;
    expect(fixture.state.data, isNull);
    expect(fixture.auth.status, AuthStatus.signedOut);
  });
}
