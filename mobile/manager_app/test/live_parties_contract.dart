// Explicit disposable-only live verification; not discovered by *_test.dart.
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:manager_app/core/config/app_config.dart';
import 'package:manager_app/core/errors/app_failure.dart';
import 'package:manager_app/core/network/api_transport.dart';
import 'package:manager_app/features/auth/data/auth_repository.dart';
import 'package:manager_app/features/auth/state/auth_controller.dart';
import 'package:manager_app/features/parties/data/party_access.dart';
import 'package:manager_app/features/parties/data/party_models.dart';
import 'package:manager_app/features/parties/data/party_repository.dart';
import 'package:manager_app/features/parties/state/party_controller.dart';

import 'support/fakes.dart';

class PartyObservedTransport implements ApiTransport {
  PartyObservedTransport(this.inner);
  final ApiTransport inner;
  // In-memory test evidence only. Never emitted or persisted.
  final responses = <({String method, String path, Object? data})>[];
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
      responses.add((method: method, path: path, data: result));
    }
    return result;
  }
}

void main() {
  final env = Platform.environment,
      url = env['MANAGER_TEST_API_URL'],
      password = env['MANAGER_TEST_PASSWORD'];
  if (url == null ||
      password == null ||
      env['MANAGER_TEST_DISPOSABLE'] != 'true') {
    throw StateError('Explicit disposable fixture required.');
  }
  ({AuthController auth, PartyObservedTransport transport}) create() {
    final dio = Dio(),
        transport = PartyObservedTransport(
          DioApiTransport(
            AppConfig(url, allowHttp: true, release: false),
            dio: dio,
          ),
        );
    dio.interceptors.add(
      InterceptorsWrapper(
        onResponse: (response, handler) {
          if (response.statusCode == 200 &&
              response.requestOptions.path.startsWith('manager/')) {
            transport.cache.add(response.headers.value('cache-control'));
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

  final range = PartyRange.custom('2026-10-01', '2026-10-07');
  for (final role in ['ADMIN', 'ACCOUNTANT', 'CASHIER', 'STOREKEEPER']) {
    test(
      'live $role parties permissions redaction literal queries summaries dates and no-store',
      () async {
        final f = create();
        try {
          expect(
            await f.auth.login('p3_$role', password),
            isTrue,
            reason: f.auth.error?.code,
          );
          final repo = PartyRepository(f.auth.repository.client);
          for (final kind in PartyKind.values) {
            final access = PartyAccess(f.auth.user, kind),
                base = 'manager/${kind.path}';
            if (!access.identity) {
              await expectLater(
                f.auth.repository.client.send('GET', base),
                throwsA(
                  isA<AppFailure>().having((e) => e.status, 'status', 403),
                ),
              );
              continue;
            }
            final all = await repo.list(
              access,
              search: 'P3-',
              sort: PartySort.code,
              size: 1,
            );
            expect(all.entries.total, 3);
            final profile = await repo.detail(
              access,
              all.entries.items.single.id,
            );
            expect(profile.phone == '12345678', isTrue);
            expect(
              profile.balance ==
                  (access.financial
                      ? (kind == PartyKind.customer ? '8.875' : '7.000')
                      : null),
              isTrue,
            );
            final raw = f.transport.responses.last.data as Map;
            expect(raw.containsKey('balance'), access.financial);
            final second = await repo.list(
              access,
              search: 'P3-',
              sort: PartySort.code,
              page: 1,
              size: 1,
            );
            expect(second.entries.items.single.id, isNot(profile.id));
            for (final literal in ['%', '_', '[', "O'Reilly", 'خاص']) {
              expect(
                (await repo.list(access, search: literal)).entries.total,
                1,
              );
            }
            expect(
              (await repo.list(access, search: "' OR 1=1 --")).entries.items,
              isEmpty,
            );
            if (access.financial) {
              final debt = await repo.list(
                access,
                mode: PartyMode.outstanding,
                size: 1,
              );
              expect(debt.entries.total, 2);
              final debtPage = await repo.list(
                access,
                mode: PartyMode.outstanding,
                page: 1,
                size: 1,
              );
              expect(
                [
                  ...debt.entries.items,
                  ...debtPage.entries.items,
                ].any((p) => !p.active),
                isTrue,
              );
              expect(
                debt.totalOutstanding ==
                    (kind == PartyKind.customer ? '28.875' : '11.000'),
                isTrue,
              );
              final credits = await repo.list(
                access,
                search: kind == PartyKind.customer ? 'P3-C3' : 'P3-S2',
              );
              expect(
                credits.entries.items.single.balance ==
                    (kind == PartyKind.customer ? '-2.000' : '-1.000'),
                isTrue,
              );
              final first = await repo.account(
                access,
                profile.id,
                range,
                size: 1,
              );
              expect(
                first.opening ==
                    (kind == PartyKind.customer ? '5.000' : '12.000'),
                isTrue,
              );
              expect(
                first.closing ==
                    (kind == PartyKind.customer ? '8.875' : '7.000'),
                isTrue,
              );
              expect(
                first.entries.items.single.runningBalance ==
                    (kind == PartyKind.customer ? '15.000' : '17.000'),
                isTrue,
              );
              expect(
                first.entries.items.single.runningBalance != first.closing,
                isTrue,
              );
              final page = await repo.account(
                access,
                profile.id,
                range,
                page: 1,
                size: 1,
              );
              expect(
                page.entries.items.single.runningBalance ==
                    (kind == PartyKind.customer ? '12.500' : '7.000'),
                isTrue,
              );
              expect(
                page.entries.items.single.date.substring(0, 10),
                kind == PartyKind.customer ? '2026-10-03' : '2026-10-07',
              );
              expect(page.closing == first.closing, isTrue);
              for (final sort in PartySort.values) {
                await repo.list(access, sort: sort);
              }
            } else {
              await expectLater(
                f.auth.repository.client.send('GET', '$base/${kind.debtPath}'),
                throwsA(
                  isA<AppFailure>().having((e) => e.status, 'status', 403),
                ),
              );
              await expectLater(
                f.auth.repository.client.send(
                  'GET',
                  '$base/${profile.id}/account',
                ),
                throwsA(
                  isA<AppFailure>().having((e) => e.status, 'status', 403),
                ),
              );
              await expectLater(
                f.auth.repository.client.send('GET', '$base?sort=balance'),
                throwsA(
                  isA<AppFailure>().having((e) => e.status, 'status', 400),
                ),
              );
            }
          }
          expect(f.transport.responses.every((r) => r.method == 'GET'), isTrue);
          expect(f.transport.cache.length, f.transport.responses.length);
          expect(
            f.transport.cache.every((c) => c?.contains('no-store') == true),
            isTrue,
          );
        } finally {
          await f.auth.logout();
          f.auth.dispose();
        }
      },
      timeout: const Timeout(Duration(minutes: 2)),
    );
  }
  test('live empty date range retains brought-forward balance; signs and full-period totals authoritative', () async {
    final f = create();
    try {
      expect(await f.auth.login('p3_ADMIN', password), isTrue);
      final repo = PartyRepository(f.auth.repository.client);
      for (final kind in PartyKind.values) {
        final access = PartyAccess(f.auth.user, kind);
        final p = (await repo.list(
          access,
          search: '100%_[',
          size: 1,
        )).entries.items.single;
        final empty = await repo.account(
          access,
          p.id,
          PartyRange.custom('2026-10-08', '2026-10-08'),
        );
        expect(empty.entries.items, isEmpty);
        expect(empty.opening == empty.closing, isTrue);
        expect(
          empty.opening == (kind == PartyKind.customer ? '8.875' : '7.000'),
          isTrue,
        );
        expect(empty.debit == '0.000' && empty.credit == '0.000', isTrue);
        final account = await repo.account(access, p.id, range);
        expect(account.debit == '10.000', isTrue);
        expect(
          account.credit == (kind == PartyKind.customer ? '6.125' : '5.000'),
          isTrue,
        );
        expect(
          account.entries.items.map((e) => e.date).toList(),
          orderedEquals(
            kind == PartyKind.customer
                ? [
                    '2026-10-01T00:00:00',
                    '2026-10-03T00:00:00',
                    '2026-10-07T00:00:00',
                  ]
                : ['2026-10-01T00:00:00', '2026-10-07T00:00:00'],
          ),
        );
      }
    } finally {
      await f.auth.logout();
      f.auth.dispose();
    }
  });
  test('live account controller pagination matches server totals without replacing header', () async {
    final f = create();
    PartyAccountController? state;
    try {
      expect(await f.auth.login('p3_ACCOUNTANT', password), isTrue);
      final repo = PartyRepository(f.auth.repository.client),
          access = PartyAccess(f.auth.user, PartyKind.customer);
      final p = (await repo.list(access, search: 'P3-C1')).entries.items.single;
      state = PartyAccountController(
        f.auth,
        repo,
        PartyKind.customer,
        p.id,
        size: 1,
      );
      state.setRange(range);
      await state.load();
      final first = state.account;
      await state.nextPage();
      await state.nextPage();
      expect(state.items.length, 3);
      expect(state.hasMore, isFalse);
      expect(state.account, same(first));
      expect(state.items.last.runningBalance == state.account!.closing, isTrue);
    } finally {
      state?.dispose();
      await f.auth.logout();
      f.auth.dispose();
    }
  });
  test(
    'live restricted session rejects protected parties in client and server',
    () async {
      final f = create();
      final state = PartyListController(
        f.auth,
        PartyRepository(f.auth.repository.client),
        PartyKind.customer,
      );
      try {
        expect(await f.auth.login('flutter_restricted', password), isTrue);
        expect(f.auth.status, AuthStatus.restricted);
        await state.load();
        expect(state.items, isEmpty);
        expect(f.transport.responses, isEmpty);
        for (final kind in PartyKind.values) {
          await expectLater(
            f.auth.repository.client.send('GET', 'manager/${kind.path}'),
            throwsA(isA<AppFailure>().having((e) => e.status, 'status', 403)),
          );
        }
      } finally {
        state.dispose();
        await f.auth.logout();
        f.auth.dispose();
      }
    },
  );
  test('live revoked session clears party identity, balances and statements through central auth', () async {
    final f = create(), second = create();
    final repo = PartyRepository(f.auth.repository.client);
    final list = PartyListController(f.auth, repo, PartyKind.customer);
    PartyDetailController? detail;
    PartyAccountController? account;
    try {
      expect(await f.auth.login('p3_ADMIN', password), isTrue);
      expect(await second.auth.login('p3_ADMIN', password), isTrue);
      await list.load();
      detail = PartyDetailController(
        f.auth,
        repo,
        PartyKind.customer,
        list.items.first.id,
      );
      account = PartyAccountController(
        f.auth,
        repo,
        PartyKind.customer,
        list.items.first.id,
      );
      account.setRange(range);
      await Future.wait([detail.load(), account.load()]);
      expect(detail.profile, isNotNull);
      expect(account.account, isNotNull);
      await second.auth.repository.revoke(f.auth.sid!);
      await detail.load();
      expect(f.auth.status, AuthStatus.signedOut);
      expect(list.items, isEmpty);
      expect(detail.profile, isNull);
      expect(account.account, isNull);
    } finally {
      list.dispose();
      detail?.dispose();
      account?.dispose();
      await f.auth.logout();
      await second.auth.logout();
      f.auth.dispose();
      second.auth.dispose();
    }
  });
}
