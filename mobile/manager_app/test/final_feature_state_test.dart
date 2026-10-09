import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:manager_app/core/errors/app_failure.dart';
import 'package:manager_app/features/auth/state/auth_controller.dart';
import 'package:manager_app/features/final_features/data/feature_access.dart';
import 'package:manager_app/features/final_features/state/feature_state.dart';
import 'package:manager_app/features/quotations/data/quotation_models.dart';
import 'package:manager_app/features/quotations/data/quotation_repository.dart';
import 'package:manager_app/features/administration/data/admin_models.dart';
import 'package:manager_app/features/administration/state/admin_mutation.dart';

import 'support/final_feature_fixtures.dart';
import 'support/fakes.dart';
import 'support/inventory_fixtures.dart';

void main() {
  Future<void> tick() => Future<void>.delayed(Duration.zero);

  test('late query callback after disposal cannot notify or request', () async {
    final f = await finalAuth();
    addTearDown(f.auth.dispose);
    var calls = 0;
    final r = QuotationRepository(f.auth.repository.client);
    final state = FeaturePaged<Quotation>(
      f.auth,
      permit: (a) => a.quotations,
      fetch: (page, size) {
        calls++;
        return r.list(FeatureAccess(f.auth.user), QuotationQuery(), page, size);
      },
    );
    state.dispose();
    state.queryChanged();
    state.queryChanged(debounce: true);
    await Future<void>.delayed(const Duration(milliseconds: 400));
    expect(calls, 0);
  });

  test('refresh singleflight and dispose ignores late completion', () async {
    final f = await finalAuth();
    addTearDown(f.auth.dispose);
    final response = Completer<Quotation>();
    var calls = 0;
    final state = FeatureState<Quotation>(
      f.auth,
      permit: (a) => a.quotations,
      request: () {
        calls++;
        return response.future;
      },
    );
    final first = state.load(), second = state.load();
    expect(identical(first, second), true);
    expect(calls, 1);
    state.dispose();
    response.complete(
      Quotation.fromJson(quotationJson(), FeatureAccess(f.auth.user)),
    );
    await first;
    expect(state.data, null);
  });
  test(
    'query invalidate rejects older completion without replacing newer data',
    () async {
      final f = await finalAuth();
      addTearDown(f.auth.dispose);
      final old = Completer<Quotation>();
      var calls = 0;
      final state = FeatureState<Quotation>(
        f.auth,
        permit: (a) => a.quotations,
        request: () => ++calls == 1
            ? old.future
            : Future.value(
                Quotation.fromJson(
                  quotationJson(id: 2),
                  FeatureAccess(f.auth.user),
                ),
              ),
      );
      addTearDown(state.dispose);
      final first = state.load();
      state.invalidate();
      await state.load();
      old.complete(
        Quotation.fromJson(quotationJson(), FeatureAccess(f.auth.user)),
      );
      await first;
      expect(state.data!.id, 2);
    },
  );
  for (final mode in ['logout', 'permission', 'restricted', 'role']) {
    test('$mode immediately invalidates protected data', () async {
      final f = await finalAuth();
      addTearDown(f.auth.dispose);
      final state = FeatureState<AdminUser>(
        f.auth,
        permit: (a) => a.users,
        request: () async => AdminUser.fromJson(adminUser()),
      );
      addTearDown(state.dispose);
      await state.load();
      expect(state.data, isNotNull);
      if (mode == 'logout') {
        await f.auth.logout();
      } else {
        f.transport.handler = (m, p, b, t) async => finalUser(
          permissions: mode == 'permission' ? [] : finalPermissions,
          role: mode == 'role' ? 'CASHIER' : 'ADMIN',
          restricted: mode == 'restricted',
        );
        await f.auth.checkSession();
      }
      expect(state.data, null);
      expect(state.allowed, false);
    });
  }
  test(
    'permission loss clears loaded quotation link before slow reload',
    () async {
      final f = await finalAuth();
      addTearDown(f.auth.dispose);
      final delayed = Completer<Quotation>();
      var calls = 0;
      final state = FeatureState<Quotation>(
        f.auth,
        permit: (a) => a.quotations,
        request: () => ++calls == 1
            ? Future.value(
                Quotation.fromJson(quotationJson(), FeatureAccess(f.auth.user)),
              )
            : delayed.future,
      );
      addTearDown(state.dispose);
      await state.load();
      expect(state.data!.linkedId, 1);
      f.transport.handler = (m, p, b, t) async =>
          finalUser(permissions: ['QUOTATIONS_VIEW']);
      await f.auth.checkSession();
      expect(state.data, null);
      delayed.complete(
        Quotation.fromJson(quotationJson(), FeatureAccess(f.auth.user)),
      );
      await tick();
      expect(state.data!.linkedId, null);
    },
  );
  test('403 clears cached data before waiting for slow /me', () async {
    final f = await finalAuth();
    addTearDown(f.auth.dispose);
    final me = Completer<Object?>();
    var fail = false;
    f.transport.handler = (m, p, b, t) => me.future;
    final state = FeatureState<Quotation>(
      f.auth,
      permit: (a) => a.quotations,
      request: () async {
        if (fail) {
          throw AppFailure.http(403, null);
        }
        return Quotation.fromJson(quotationJson(), FeatureAccess(f.auth.user));
      },
    );
    addTearDown(state.dispose);
    await state.load();
    fail = true;
    final pending = state.load();
    await tick();
    expect(state.data, null);
    expect(state.loading, false);
    expect(state.error!.status, 403);
    me.complete(finalUser());
    await pending;
  });
  test('transient refresh retains prior safe data and reports error', () async {
    final f = await finalAuth();
    addTearDown(f.auth.dispose);
    var failed = false;
    final state = FeatureState<Quotation>(
      f.auth,
      permit: (a) => a.quotations,
      request: () async {
        if (failed) {
          throw const AppFailure('TIMEOUT', 'تعذر الاتصال.');
        }
        return Quotation.fromJson(quotationJson(), FeatureAccess(f.auth.user));
      },
    );
    addTearDown(state.dispose);
    await state.load();
    final before = state.data;
    failed = true;
    await state.load();
    expect(state.data, same(before));
    expect(state.error!.code, 'TIMEOUT');
    expect(state.loading, false);
  });
  test(
    'pagination singleflight, same-page retry and overlap deduplication',
    () async {
      final f = await finalAuth();
      addTearDown(f.auth.dispose);
      final repository = QuotationRepository(f.auth.repository.client),
          query = QuotationQuery();
      final pending = Completer<Object?>();
      var fail = true, attempts = 0;
      f.transport.handler = (m, p, b, t) async {
        final page = int.parse(Uri.parse(p).queryParameters['page']!);
        if (page == 0) {
          return inventoryPage([quotationJson()], size: 1, total: 3);
        }
        attempts++;
        if (fail) {
          throw const AppFailure('TIMEOUT', 'تعذر الاتصال.');
        }
        return pending.future;
      };
      final state = FeaturePaged<Quotation>(
        f.auth,
        permit: (a) => a.quotations,
        size: 1,
        identity: (q) => q.id,
        fetch: (page, size) =>
            repository.list(FeatureAccess(f.auth.user), query, page, size),
      );
      addTearDown(state.dispose);
      await state.load();
      await state.nextPage();
      expect(state.page, 0);
      expect(state.moreError, isNotNull);
      fail = false;
      final first = state.nextPage(), second = state.nextPage();
      expect(identical(first, second), true);
      pending.complete(
        inventoryPage([quotationJson()], page: 1, size: 1, total: 3),
      );
      await first;
      expect(attempts, 2);
      expect(state.page, 1);
      expect(state.items.length, 1);
    },
  );
  test('refresh supersedes pending next page', () async {
    final f = await finalAuth();
    addTearDown(f.auth.dispose);
    final latePage = Completer<Object?>();
    var first = true;
    f.transport.handler = (m, p, b, t) async {
      final page = int.parse(Uri.parse(p).queryParameters['page']!);
      if (page == 1) return latePage.future;
      final id = first ? 1 : 3;
      first = false;
      return inventoryPage([quotationJson(id: id)], size: 1, total: 3);
    };
    final r = QuotationRepository(f.auth.repository.client);
    final state = FeaturePaged<Quotation>(
      f.auth,
      permit: (a) => a.quotations,
      size: 1,
      fetch: (page, size) =>
          r.list(FeatureAccess(f.auth.user), QuotationQuery(), page, size),
    );
    addTearDown(state.dispose);
    await state.load();
    final next = state.nextPage();
    await state.load();
    latePage.complete(
      inventoryPage([quotationJson(id: 2)], page: 1, size: 1, total: 3),
    );
    await next;
    expect(state.items.single.id, 3);
    expect(state.page, 0);
  });
  test(
    'empty next page terminates pagination despite changing total',
    () async {
      final f = await finalAuth();
      addTearDown(f.auth.dispose);
      f.transport.handler = (m, p, b, t) async {
        final page = int.parse(Uri.parse(p).queryParameters['page']!);
        return inventoryPage(
          page == 0 ? [quotationJson()] : [],
          page: page,
          size: 1,
          total: 3,
        );
      };
      final r = QuotationRepository(f.auth.repository.client);
      final state = FeaturePaged<Quotation>(
        f.auth,
        permit: (a) => a.quotations,
        size: 1,
        fetch: (page, size) =>
            r.list(FeatureAccess(f.auth.user), QuotationQuery(), page, size),
      );
      addTearDown(state.dispose);
      await state.load();
      await state.nextPage();
      expect(state.hasMore, false);
    },
  );
  test(
    'search debounce clears immediately and superseded search is not sent',
    () async {
      final f = await finalAuth();
      addTearDown(f.auth.dispose);
      final r = QuotationRepository(f.auth.repository.client),
          q = QuotationQuery();
      final state = FeaturePaged<Quotation>(
        f.auth,
        permit: (a) => a.quotations,
        fetch: (page, size) =>
            r.list(FeatureAccess(f.auth.user), q, page, size),
      );
      addTearDown(state.dispose);
      await state.load();
      q.search = 'old';
      state.queryChanged(debounce: true);
      expect(state.items, isEmpty);
      q.search = 'new';
      state.queryChanged(debounce: true);
      await Future<void>.delayed(const Duration(milliseconds: 400));
      expect(
        Uri.parse(f.transport.calls.last.path).queryParameters['q'],
        'new',
      );
      expect(f.transport.calls.length, 2);
    },
  );
  test('centralized401 refresh remains shared across feature reads', () async {
    final f = await finalAuth();
    addTearDown(f.auth.dispose);
    var refreshes = 0;
    f.transport.handler = (m, p, b, t) async {
      if (p == 'auth/refresh') {
        refreshes++;
        await tick();
        return {...loginJson(access: 'new-access'), 'user': finalUser()};
      }
      if (p == 'auth/me') return finalUser();
      if (t != 'new-access') {
        throw AppFailure.http(401, null);
      }
      return finalResponse(p);
    };
    final r = QuotationRepository(f.auth.repository.client);
    await Future.wait([
      r.list(FeatureAccess(f.auth.user), QuotationQuery(), 0, 20),
      r.detail(FeatureAccess(f.auth.user), 1, 0, 20),
    ]);
    expect(refreshes, 1);
    expect(f.auth.status, AuthStatus.authenticated);
  });
  test('terminal401 clears every feature state', () async {
    final f = await finalAuth();
    addTearDown(f.auth.dispose);
    final r = QuotationRepository(f.auth.repository.client);
    final state = FeaturePaged<Quotation>(
      f.auth,
      permit: (a) => a.quotations,
      fetch: (page, size) =>
          r.list(FeatureAccess(f.auth.user), QuotationQuery(), page, size),
    );
    addTearDown(state.dispose);
    await state.load();
    f.transport.handler = (m, p, b, t) async =>
        throw AppFailure.http(401, null);
    await state.load();
    expect(state.items, isEmpty);
    expect(f.auth.status, AuthStatus.signedOut);
  });
  test('admin prevents parallel/double writes and publishes only confirmed success', () async {
    final f = await finalAuth();
    addTearDown(f.auth.dispose);
    final mutation = AdminMutation(f.auth), result = Completer<AdminUser>();
    addTearDown(mutation.dispose);
    var calls = 0;
    final first = mutation.run((a) => a.edit, () {
      calls++;
      return result.future;
    });
    expect(mutation.busy, true);
    expect(mutation.result, null);
    expect(
      await mutation.run((a) => a.edit, () {
        calls++;
        return result.future;
      }),
      false,
    );
    result.complete(AdminUser.fromJson(adminUser()));
    expect(await first, true);
    expect(calls, 1);
    expect(mutation.busy, false);
  });
  for (final failure in [
    const AppFailure('TIMEOUT', 'safe'),
    AppFailure.http(503, null),
    AppFailure.malformed,
  ]) {
    test(
      'ambiguous ${failure.code} locks writes until reread and acknowledgement',
      () async {
        final f = await finalAuth();
        addTearDown(f.auth.dispose);
        final mutation = AdminMutation(f.auth);
        addTearDown(mutation.dispose);
        var calls = 0;
        await mutation.run((a) => a.reset, () async {
          calls++;
          throw failure;
        });
        expect(mutation.uncertain, true);
        expect(mutation.result, null);
        mutation.acknowledge();
        expect(mutation.uncertain, true);
        await mutation.run((a) => a.reset, () async {
          calls++;
          return AdminUser.fromJson(adminUser());
        });
        expect(calls, 1);
        mutation.stateReread();
        mutation.acknowledge();
        expect(mutation.uncertain, false);
      },
    );
  }
  test(
    'server400 last-admin rejection is safe and never optimistic success',
    () async {
      final f = await finalAuth();
      addTearDown(f.auth.dispose);
      final mutation = AdminMutation(f.auth);
      addTearDown(mutation.dispose);
      expect(
        await mutation.run(
          (a) => a.edit,
          () async => throw AppFailure.http(400, {'message': 'sql raw secret'}),
        ),
        false,
      );
      expect(mutation.uncertain, false);
      expect(mutation.result, null);
      expect(mutation.error!.message, isNot(contains('sql raw')));
    },
  );
  test(
    'admin permission loss while write pending cannot publish protected result',
    () async {
      final f = await finalAuth();
      addTearDown(f.auth.dispose);
      final mutation = AdminMutation(f.auth), response = Completer<AdminUser>();
      addTearDown(mutation.dispose);
      final pending = mutation.run((a) => a.edit, () => response.future);
      f.transport.handler = (m, p, b, t) async => finalUser(permissions: []);
      await f.auth.checkSession();
      response.complete(AdminUser.fromJson(adminUser()));
      expect(await pending, false);
      expect(mutation.result, null);
    },
  );
}
