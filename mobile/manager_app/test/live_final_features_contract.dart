// Explicit disposable-only live suite; excluded from the normal *_test.dart run.
import 'dart:convert';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:manager_app/core/config/app_config.dart';
import 'package:manager_app/core/errors/app_failure.dart';
import 'package:manager_app/core/network/api_transport.dart';
import 'package:manager_app/features/auth/data/auth_repository.dart';
import 'package:manager_app/features/auth/state/auth_controller.dart';
import 'package:manager_app/features/final_features/data/feature_access.dart';
import 'package:manager_app/features/quotations/data/quotation_models.dart';
import 'package:manager_app/features/quotations/data/quotation_repository.dart';
import 'package:manager_app/features/reports/data/report_repository.dart';
import 'package:manager_app/features/audit/data/audit_repository.dart';
import 'package:manager_app/features/administration/data/admin_models.dart';
import 'package:manager_app/features/administration/data/admin_repository.dart';
import 'package:manager_app/features/inventory/data/inventory_models.dart';

import 'support/fakes.dart';

class FinalObservedTransport implements ApiTransport {
  FinalObservedTransport(this.inner);
  final ApiTransport inner;
  final manager = <({String method, String path, Object? data})>[];
  final cache = <String?>[];
  @override
  Future<Object?> send(
    String method,
    String path, {
    Object? body,
    String? accessToken,
  }) async {
    final result = await inner.send(
      method,
      path,
      body: body,
      accessToken: accessToken,
    );
    if (path.startsWith('manager/')) {
      manager.add((method: method, path: path, data: result));
    }
    return result;
  }
}

void main() {
  final env = Platform.environment,
      url = Platform.environment['MANAGER_TEST_API_URL'],
      password = Platform.environment['MANAGER_TEST_PASSWORD'],
      nextPassword = Platform.environment['MANAGER_TEST_NEW_PASSWORD'];
  if (url == null ||
      password == null ||
      nextPassword == null ||
      env['MANAGER_TEST_DISPOSABLE'] != 'true') {
    throw StateError(
      'Explicit disposable fixture and runtime credentials required.',
    );
  }
  ({AuthController auth, FinalObservedTransport transport, MemoryVault vault})
  create() {
    final dio = Dio(),
        observed = FinalObservedTransport(
          DioApiTransport(
            AppConfig(url, allowHttp: true, release: false),
            dio: dio,
          ),
        );
    dio.interceptors.add(
      InterceptorsWrapper(
        onResponse: (response, handler) {
          if (response.statusCode != null &&
              response.statusCode! >= 200 &&
              response.statusCode! < 300 &&
              response.requestOptions.path.startsWith('manager/')) {
            observed.cache.add(response.headers.value('cache-control'));
          }
          handler.next(response);
        },
      ),
    );
    final vault = MemoryVault();
    return (
      auth: AuthController(AuthRepository(observed), vault),
      transport: observed,
      vault: vault,
    );
  }

  final forbidden = throwsA(
    isA<AppFailure>().having((e) => e.status, 'status', 403),
  );
  final unauthorized = throwsA(
    isA<AppFailure>().having((e) => e.status, 'status', 401),
  );
  final range = MovementRange.custom('2026-10-01', '2026-10-07');
  for (final role in ['ADMIN', 'ACCOUNTANT', 'CASHIER', 'STOREKEEPER']) {
    test(
      'live $role quotations/report/audit/admin authorization and no-store',
      () async {
        final f = create();
        try {
          expect(
            await f.auth.login('p3_$role', password),
            true,
            reason: f.auth.error?.code,
          );
          final a = FeatureAccess(f.auth.user),
              quotes = QuotationRepository(f.auth.repository.client),
              reports = ReportRepository(f.auth.repository.client);
          if (a.quotations) {
            final q = QuotationQuery(
              range: MovementRange.custom('2026-11-01', '2026-11-01'),
              search: 'P6-Q-',
              sort: QuotationSort.expiry,
            );
            final result = await quotes.list(a, q, 0, 20);
            expect(result.entries.total, 6);
            expect(result.entries.items.every((q) => q.pastValidity), true);
            expect(
              result.entries.items.map((q) => q.status).toSet(),
              QuotationStatus.values.map((v) => v.api).toSet(),
            );
            final accepted = result.entries.items.firstWhere(
              (q) => q.status == 'ACCEPTED',
            );
            final detail = await quotes.detail(a, accepted.id, 0, 1),
                header = detail.header as Quotation;
            expect(header.total == '8.000', true);
            expect(detail.entries.items.single.quantity == '2.500', true);
            expect(header.linkedStatus, a.saleLinks ? 'DRAFT' : null);
            expect(header.status, 'ACCEPTED');
          } else {
            await expectLater(
              f.auth.repository.client.send('GET', 'manager/quotations'),
              forbidden,
            );
          }
          if (a.analytics!.expenses) {
            final e = await reports.expenses(a, range);
            expect(e.summary.total == '4.125', true);
          }
          if (a.analytics!.cash) {
            final c = await reports.cash(a, range);
            expect(c.opening == '50.000', true);
            expect(c.closing == '63.875', true);
          }
          final d = await reports.daily(a, '2026-10-07');
          if (a.analytics!.sales) {
            expect(d.sales!.netSales == '6.875', true);
            expect(d.sales!.netProfit != null, a.has('REPORTS_PROFIT'));
          }
          if (a.analytics!.inventory) {
            final slow = await reports.slow(a, 30, 'P3-', 0, 1);
            expect(slow.entries.size, 1);
          }
          if (a.audit) {
            await AuditRepository(f.auth.repository.client)
                .list(a, AuditQuery(), 0, 20);
            final metadata = await AdminRepository(f.auth.repository.client)
                .metadata(a);
            expect(metadata.roles.length, 4);
          } else {
            await expectLater(
              f.auth.repository.client.send('GET', 'manager/audit'),
              forbidden,
            );
            await expectLater(
              f.auth.repository.client.send('GET', 'manager/admin/users'),
              forbidden,
            );
          }
          expect(f.transport.manager.every((r) => r.method == 'GET'), true);
          expect(f.transport.cache.isNotEmpty, true);
          expect(f.transport.cache.every((v) => v == 'no-store'), true);
        } finally {
          await f.auth.logout();
          f.auth.dispose();
        }
      },
    );
  }
  test('live quotation literal search, nullable prospect, expiry boundary and nested pages', () async {
    final f = create();
    try {
      expect(await f.auth.login('p3_ADMIN', password), true);
      final a = FeatureAccess(f.auth.user),
          repo = QuotationRepository(f.auth.repository.client);
      final q = QuotationQuery(
        range: MovementRange.custom('2026-11-01', '2026-11-01'),
        search: '100%_[',
        status: QuotationStatus.accepted,
        pastValidity: true,
        customerId: int.parse(env['MANAGER_TEST_CUSTOMER_ID']!),
        sort: QuotationSort.totalAscending,
      );
      final found = await repo.list(a, q, 0, 1);
      expect(found.entries.total, 1);
      final first = await repo.detail(a, found.entries.items.single.id, 0, 1),
          second = await repo.detail(a, found.entries.items.single.id, 1, 1);
      expect(first.entries.total, 2);
      expect(
        second.entries.items.single.id != first.entries.items.single.id,
        true,
      );
      q.status = null;
      q.customerId = null;
      q.pastValidity = false;
      q.search = 'P6-PROSPECT';
      final prospect = (await repo.list(a, q, 0, 20)).entries.items.single;
      expect(prospect.customerId, null);
      expect(prospect.prospectName != null, true);
      expect(prospect.validUntil, null);
      expect(prospect.pastValidity, false);
      q.search = 'P6-TODAY';
      final today = (await repo.list(a, q, 0, 20)).entries.items.single;
      expect(today.pastValidity, false);
      expect(today.validUntil!.iso, today.businessDate.iso);
      expect(f.transport.manager.every((r) => r.method == 'GET'), true);
    } finally {
      await f.auth.logout();
      f.auth.dispose();
    }
  });
  test('live malformed filter request remains safely rejected', () async {
    final f = create();
    try {
      expect(await f.auth.login('p3_ADMIN', password), true);
      for (final path in [
        'manager/audit?action=OTHER',
        'manager/audit?category=Secret',
        'manager/quotations?size=101',
        'manager/admin/users?role=MANAGER',
      ]) {
        await expectLater(
          f.auth.repository.client.send('GET', path),
          throwsA(isA<AppFailure>().having((e) => e.status, 'status', 400)),
        );
      }
    } finally {
      await f.auth.logout();
      f.auth.dispose();
    }
  });
  test('live complete disposable user lifecycle invalidates credentials and never resurrects sessions', () async {
    final f = create(), target = create();
    try {
      expect(await f.auth.login('p3_ADMIN', password), true);
      final a = FeatureAccess(f.auth.user),
          repo = AdminRepository(f.auth.repository.client),
          metadata = await repo.metadata(a);
      final username = 'p6new_${DateTime.now().microsecondsSinceEpoch}';
      final u = await repo.create(
        a,
        metadata,
        username,
        AdminProfile(
          fullName: 'مستخدم مؤقت',
          roleCode: 'CASHIER',
          active: true,
        ),
        password,
        password,
      );
      expect(u.mustChange, true);
      expect(u.active, true);
      expect(u.username, username);
      expect(await target.auth.login(username, password), true);
      expect(target.auth.status, AuthStatus.restricted);
      await expectLater(
        target.auth.repository.client.send('GET', 'manager/quotations'),
        forbidden,
      );
      expect(
        await target.auth.changePassword(password, nextPassword, nextPassword),
        true,
      );
      expect(await target.auth.login(username, nextPassword), true);
      expect(target.auth.status, AuthStatus.authenticated);
      final oldAccess = target.auth.accessToken!,
          oldRefresh = target.vault.value!;
      final edited = await repo.edit(
        a,
        metadata,
        u,
        AdminProfile(
          fullName: 'الاسم المعدل',
          roleCode: 'ACCOUNTANT',
          active: true,
        ),
      );
      expect(edited.roleCode, 'ACCOUNTANT');
      expect(edited.username, username);
      await expectLater(
        target.transport.inner.send('GET', 'auth/me', accessToken: oldAccess),
        unauthorized,
      );
      await expectLater(
        target.transport.inner.send(
          'POST',
          'auth/refresh',
          body: {'refreshToken': oldRefresh},
        ),
        unauthorized,
      );
      await target.auth.checkSession();
      expect(target.auth.status, AuthStatus.signedOut);
      expect(await target.auth.login(username, nextPassword), true);
      expect(target.auth.user!.allows('REPORTS_PROFIT'), true);
      final beforeDisable = target.auth.accessToken!,
          refreshBeforeDisable = target.vault.value!;
      expect((await repo.active(a, edited, false)).active, false);
      await expectLater(
        target.transport.inner.send(
          'GET',
          'auth/me',
          accessToken: beforeDisable,
        ),
        unauthorized,
      );
      expect((await repo.active(a, edited, true)).active, true);
      await expectLater(
        target.transport.inner.send(
          'GET',
          'auth/me',
          accessToken: beforeDisable,
        ),
        unauthorized,
      );
      await expectLater(
        target.transport.inner.send(
          'POST',
          'auth/refresh',
          body: {'refreshToken': refreshBeforeDisable},
        ),
        unauthorized,
      );
      await target.auth.checkSession();
      expect(await target.auth.login(username, nextPassword), true);
      final beforeReset = target.auth.accessToken!,
          refreshBeforeReset = target.vault.value!;
      final reset = await repo.reset(a, edited, password, password);
      expect(reset.mustChange, true);
      await expectLater(
        target.transport.inner.send('GET', 'auth/me', accessToken: beforeReset),
        unauthorized,
      );
      await expectLater(
        target.transport.inner.send(
          'POST',
          'auth/refresh',
          body: {'refreshToken': refreshBeforeReset},
        ),
        unauthorized,
      );
      await target.auth.checkSession();
      expect(await target.auth.login(username, password), true);
      expect(target.auth.status, AuthStatus.restricted);
      final q = AdminQuery()..search = username;
      final list = await repo.list(a, q, 0, 20, metadata: metadata);
      expect(list.entries.items.single.id, u.id);
      expect((await repo.detail(a, u.id)).username, username);
      for (final r in f.transport.manager) {
        final text = jsonEncode(r.data);
        expect(text.contains(password) || text.contains(nextPassword), false);
        expect(
          text.contains('passwordHash') ||
              text.contains('credentialFingerprint'),
          false,
        );
        if (r.method != 'GET') {
          expect(r.path.startsWith('manager/admin/users'), true);
          expect(r.method == 'POST' || r.method == 'PUT', true);
        }
      }
      final audit = AuditQuery()
        ..action = 'PASSWORD_RESET'
        ..category = 'Users'
        ..userId = f.auth.user!.id;
      final events = await AuditRepository(f.auth.repository.client)
          .list(a, audit, 0, 20);
      expect(events.entries.items.isNotEmpty, true);
      final raw = f.transport.manager.last.data as Map;
      for (final row in raw['items'] as List) {
        expect(
          (row as Map).keys.any(
            (key) => [
              'description',
              'oldValues',
              'newValues',
              'machineName',
              'password',
            ].contains(key),
          ),
          false,
        );
      }
    } finally {
      await target.auth.logout();
      target.auth.dispose();
      await f.auth.logout();
      f.auth.dispose();
    }
  });
  test(
    'live restricted credentials cannot retain final feature data',
    () async {
      final f = create();
      try {
        expect(await f.auth.login('flutter_restricted', password), true);
        expect(f.auth.status, AuthStatus.restricted);
        for (final path in [
          'manager/quotations',
          'manager/audit',
          'manager/admin/users',
          'manager/daily-summary',
        ]) {
          await expectLater(
            f.auth.repository.client.send('GET', path),
            forbidden,
          );
        }
      } finally {
        await f.auth.logout();
        f.auth.dispose();
      }
    },
  );
  test('live concurrent administrator removal retains an active admin and rejects unsafe operation', () async {
    final first = create(), second = create();
    try {
      expect(await first.auth.login('p3_ADMIN', password), true);
      expect(await second.auth.login('p6_second_admin', password), true);
      Future<int> disable(AuthController actor, int target) async {
        try {
          await actor.repository.client.send(
            'POST',
            'manager/admin/users/$target/disable',
          );
          return 200;
        } on AppFailure catch (e) {
          return e.status ?? 0;
        }
      }

      final outcomes = await Future.wait([
        disable(first.auth, second.auth.user!.id),
        disable(second.auth, first.auth.user!.id),
      ]);
      expect(outcomes.where((s) => s == 200).length, 1);
      expect(outcomes.any((s) => s == 400 || s == 401), true);
      final survivor = outcomes[0] == 200 ? first : second;
      final repo = AdminRepository(survivor.auth.repository.client),
          a = FeatureAccess(survivor.auth.user);
      final metadata = await repo.metadata(a),
          q = AdminQuery()
            ..role = 'ADMIN'
            ..active = true;
      final admins = await repo.list(a, q, 0, 20, metadata: metadata);
      expect(admins.entries.total, 1);
      await expectLater(
        survivor.auth.repository.client.send(
          'POST',
          'manager/admin/users/${survivor.auth.user!.id}/disable',
        ),
        throwsA(isA<AppFailure>().having((e) => e.status, 'status', 400)),
      );
    } finally {
      await first.auth.logout();
      first.auth.dispose();
      await second.auth.logout();
      second.auth.dispose();
    }
  });
}
