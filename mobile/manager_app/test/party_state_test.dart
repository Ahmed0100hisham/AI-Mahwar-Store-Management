import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:manager_app/core/errors/app_failure.dart';
import 'package:manager_app/features/auth/state/auth_controller.dart';
import 'package:manager_app/features/parties/data/party_access.dart';
import 'package:manager_app/features/parties/data/party_models.dart';
import 'package:manager_app/features/parties/data/party_repository.dart';
import 'package:manager_app/features/parties/state/party_controller.dart';

import 'support/fakes.dart';
import 'support/inventory_fixtures.dart';
import 'support/party_fixtures.dart';

void main() {
  Future<
    ({
      PartyListController list,
      PartyDetailController detail,
      PartyAccountController account,
      AuthController auth,
      FakeTransport transport,
    })
  >
  setup(
    PartyKind kind, {
    List<String> permissions = partyPermissions,
    bool restricted = false,
  }) async {
    final f = await partyAuth(permissions: permissions, restricted: restricted);
    final repo = PartyRepository(f.auth.repository.client);
    final list = PartyListController(f.auth, repo, kind, size: 1),
        detail = PartyDetailController(f.auth, repo, kind, 1),
        account = PartyAccountController(f.auth, repo, kind, 1, size: 1);
    addTearDown(() {
      list.dispose();
      detail.dispose();
      account.dispose();
      f.auth.dispose();
    });
    return (
      list: list,
      detail: detail,
      account: account,
      auth: f.auth,
      transport: f.transport,
    );
  }

  for (final kind in PartyKind.values) {
    final prefix = 'manager/${kind.path}';
    test(
      '${kind.name}: initial loading and refresh singleflight retain safe same-scope rows',
      () async {
        final f = await setup(kind), gate = Completer<Object?>();
        f.transport.handler = (_, _, _, _) => gate.future;
        final pending = f.list.load();
        expect(f.list.loading, isTrue);
        expect(f.list.items, isEmpty);
        expect(f.list.load(), same(pending));
        gate.complete(partyResponse('$prefix?size=1'));
        await pending;
        expect(f.list.page, 0);
        expect(f.list.total, 3);
        expect(f.list.hasMore, isTrue);
        f.transport.handler = (_, _, _, _) async =>
            throw AppFailure.http(503, null);
        await f.list.load();
        expect(f.list.items.single.id, 1);
        expect(f.list.error!.status, 503);
        expect(f.list.canLoadMore, isFalse);
      },
    );
    test(
      '${kind.name}: search clears previous context immediately and newest wins',
      () async {
        final f = await setup(kind), old = Completer<Object?>();
        await f.list.load();
        f.transport.handler = (_, p, _, _) =>
            Uri.parse(p).queryParameters['q'] == 'old'
            ? old.future
            : Future.value({
                'page': inventoryPage([partyJson(kind, 2)], size: 1),
              });
        f.list.setSearch('old', immediate: true);
        final pending = f.list.load();
        expect(f.list.items, isEmpty);
        f.list.setSearch('new', immediate: true);
        await f.list.load();
        old.complete({
          'page': inventoryPage([partyJson(kind, 1)], size: 1),
        });
        await pending;
        expect(f.list.items.single.id, 2);
        expect(f.list.loadedContext, contains('new'));
        f.list.setSearch('');
        await f.list.load();
        expect(
          Uri.parse(f.transport.calls.last.path).queryParameters
              .containsKey('q'),
          isFalse,
        );
      },
    );
    test(
      '${kind.name}: debounce superseded search and clear cancel timer',
      () async {
        final f = await setup(kind);
        f.transport.calls.clear();
        f.list.setSearch('first');
        f.list.setSearch('second');
        expect(f.list.searching, isTrue);
        expect(f.transport.calls, isEmpty);
        await Future<void>.delayed(const Duration(milliseconds: 400));
        expect(f.transport.calls.length, 1);
        expect(
          Uri.parse(f.transport.calls.single.path).queryParameters['q'],
          'second',
        );
        f.list.setSearch('cancel');
        f.list.setSearch('');
        await f.list.load();
        final count = f.transport.calls.length;
        await Future<void>.delayed(const Duration(milliseconds: 400));
        expect(f.transport.calls.length, count);
      },
    );
    test(
      '${kind.name}: mode and sort reset page and authoritative debt summary',
      () async {
        final f = await setup(kind);
        await f.list.load();
        await f.list.nextPage();
        f.list.setMode(PartyMode.outstanding);
        await f.list.load();
        expect(f.list.page, 0);
        expect(
          f.list.totalOutstanding,
          kind == PartyKind.customer ? '28.875' : '11.000',
        );
        expect(f.list.sort, PartySort.balanceDescending);
        f.list.setSort(PartySort.codeDescending);
        await f.list.load();
        expect(f.list.items.single.id, 2);
        expect(f.list.page, 0);
        f.list.setMode(PartyMode.all);
        await f.list.load();
        expect(f.list.totalOutstanding, isNull);
      },
    );
    test(
      '${kind.name}: next page singleflight, failure retries same page without skipping',
      () async {
        final f = await setup(kind);
        await f.list.load();
        final gate = Completer<Object?>();
        f.transport.handler = (_, _, _, _) => gate.future;
        final pending = f.list.nextPage();
        expect(f.list.nextPage(), same(pending));
        expect(f.list.loadingMore, isTrue);
        gate.completeError(AppFailure.http(503, null));
        await pending;
        expect(f.list.page, 0);
        expect(f.list.nextError, isNotNull);
        f.transport.handler = (_, p, _, _) async => partyResponse(p);
        await f.list.nextPage();
        expect(f.list.page, 1);
        expect(f.list.items.map((p) => p.id), [1, 2]);
        expect(
          Uri.parse(f.transport.calls.last.path).queryParameters['page'],
          '1',
        );
      },
    );
    test(
      '${kind.name}: overlapping party IDs deduplicate and empty next page stops loading',
      () async {
        final f = await setup(kind);
        await f.list.load();
        f.transport.handler = (_, _, _, _) async => {
          'page': inventoryPage(
            [partyJson(kind, 1)],
            page: 1,
            size: 1,
            total: 4,
          ),
        };
        await f.list.nextPage();
        expect(f.list.items.length, 1);
        expect(f.list.page, 1);
        f.transport.handler = (_, _, _, _) async => {
          'page': inventoryPage([], page: 2, size: 1, total: 4),
        };
        await f.list.nextPage();
        expect(f.list.hasMore, isFalse);
      },
    );
    test('${kind.name}: refresh invalidates stale list page append', () async {
      final f = await setup(kind);
      await f.list.load();
      final old = Completer<Object?>();
      f.transport.handler = (_, _, _, _) => old.future;
      final pending = f.list.nextPage();
      f.transport.handler = (_, p, _, _) async => partyResponse(p);
      await f.list.load();
      old.complete(partyResponse('$prefix?page=1&size=1'));
      await pending;
      expect(f.list.page, 0);
      expect(f.list.items.length, 1);
    });
    test(
      '${kind.name}: detail switch rejects old party response and refresh is singleflight',
      () async {
        final f = await setup(kind), old = Completer<Object?>();
        f.transport.handler = (_, p, _, _) =>
            p.endsWith('/1') ? old.future : Future.value(partyJson(kind, 2));
        final pending = f.detail.load();
        expect(f.detail.load(), same(pending));
        await f.detail.selectParty(2);
        old.complete(partyJson(kind, 1));
        await pending;
        expect(f.detail.profile!.id, 2);
        f.transport.handler = (_, _, _, _) async =>
            throw AppFailure.http(503, null);
        await f.detail.load();
        expect(f.detail.profile!.id, 2);
        expect(f.detail.error, isNotNull);
      },
    );
    test(
      '${kind.name}: account pages anchor server range and preserve full closing header',
      () async {
        final f = await setup(kind);
        await f.account.load();
        final closing = f.account.account!.closing;
        await f.account.nextPage();
        final q = Uri.parse(f.transport.calls.last.path).queryParameters;
        expect(q['from'], '2026-10-01');
        expect(q['to'], '2026-10-31');
        expect(q['page'], '1');
        expect(f.account.account!.closing, closing);
        expect(f.account.items.length, 2);
      },
    );
    test(
      '${kind.name}: account page failure retry and concurrent requests are singleflight',
      () async {
        final f = await setup(kind);
        await f.account.load();
        final gate = Completer<Object?>();
        f.transport.handler = (_, _, _, _) => gate.future;
        final pending = f.account.nextPage();
        expect(f.account.nextPage(), same(pending));
        gate.completeError(AppFailure.http(503, null));
        await pending;
        expect(f.account.page, 0);
        f.transport.handler = (_, p, _, _) async => partyResponse(p);
        await f.account.nextPage();
        expect(f.account.page, 1);
        expect(f.account.nextError, isNull);
      },
    );
    test(
      '${kind.name}: statement date changes clear header and suppress old first/page results',
      () async {
        final f = await setup(kind);
        await f.account.load();
        final old = Completer<Object?>();
        f.transport.handler = (_, _, _, _) => old.future;
        final pending = f.account.nextPage();
        f.transport.handler = (_, p, _, _) async => partyResponse(p);
        f.account.setRange(PartyRange.custom('2026-10-07', '2026-10-07'));
        expect(f.account.account, isNull);
        expect(f.account.items, isEmpty);
        await f.account.load();
        old.complete(partyAccountJson(kind, page: 1, size: 1));
        await pending;
        expect(f.account.account!.range.from.iso, '2026-10-07');
        expect(f.account.page, 0);
      },
    );
    test(
      '${kind.name}: changing selected statement party discards pending old account',
      () async {
        final f = await setup(kind), old = Completer<Object?>();
        f.transport.handler = (_, p, _, _) =>
            Uri.parse(p).path == '$prefix/1/account'
            ? old.future
            : Future.value(partyAccountJson(kind, id: 2, size: 1));
        final pending = f.account.load();
        f.account.selectParty(2);
        await f.account.load();
        old.complete(partyAccountJson(kind, size: 1));
        await pending;
        expect(f.account.account!.partyId, 2);
      },
    );
    test(
      '${kind.name}: identical ledger entries on later page are not discarded',
      () async {
        final f = await setup(kind);
        await f.account.load();
        f.transport.handler = (_, _, _, _) async => {
          ...partyAccountJson(kind),
          'entries': inventoryPage(
            [partyEntry(kind, 1)],
            page: 1,
            size: 1,
            total: 2,
          ),
        };
        await f.account.nextPage();
        expect(f.account.items.length, 2);
        expect(f.account.items.first.date, f.account.items.last.date);
      },
    );
    for (final status in [403, 404]) {
      test(
        '${kind.name}: $status clears financial data before slow me validation',
        () async {
          final f = await setup(kind);
          await Future.wait([f.list.load(), f.detail.load(), f.account.load()]);
          final me = Completer<Object?>();
          f.transport.handler = (_, p, _, _) => p == 'auth/me'
              ? me.future
              : Future.error(AppFailure.http(status, null));
          final pending = f.account.nextPage();
          await Future<void>.delayed(Duration.zero);
          expect(f.account.account, isNull);
          expect(f.account.items, isEmpty);
          expect(f.account.error!.status, status);
          me.complete(userJson(permissions: partyPermissions));
          await pending;
        },
      );
    }
    test(
      '${kind.name}: balance revocation clears all models immediately, identity remains usable',
      () async {
        final f = await setup(kind);
        await Future.wait([f.list.load(), f.detail.load(), f.account.load()]);
        final reload = Completer<Object?>(),
            codes = partyPermissions
                .where((c) => c != kind.balanceCode)
                .toList();
        f.transport.handler = (_, p, _, _) => p == 'auth/me'
            ? Future.value(userJson(permissions: codes))
            : reload.future;
        await f.auth.revalidate();
        expect(f.list.items, isEmpty);
        expect(f.detail.profile, isNull);
        expect(f.account.account, isNull);
        expect(f.account.allowed, isFalse);
        reload.complete(partyJson(kind, 1));
        await f.detail.load();
        expect(f.detail.profile?.balance, isNull);
      },
    );
    for (final reason in ['logout', 'identity', 'password']) {
      test(
        '${kind.name}: $reason invalidates pending sensitive responses',
        () async {
          final f = await setup(kind);
          await Future.wait([f.list.load(), f.detail.load(), f.account.load()]);
          final old = Completer<Object?>();
          f.transport.handler = (_, p, _, _) => p == 'auth/me'
              ? Future.value(
                  userJson(
                    permissions: reason == 'identity' ? [] : partyPermissions,
                    restricted: reason == 'password',
                  ),
                )
              : old.future;
          final pending = f.account.nextPage();
          if (reason == 'logout') {
            await f.auth.terminate();
          } else {
            await f.auth.revalidate();
          }
          expect(f.list.items, isEmpty);
          expect(f.detail.profile, isNull);
          expect(f.account.items, isEmpty);
          old.complete(partyAccountJson(kind, page: 1, size: 1));
          await pending;
          expect(f.account.account, isNull);
        },
      );
    }
    test(
      '${kind.name}: identity-only or restricted users make no financial requests',
      () async {
        final f = await setup(kind, permissions: [kind.identityCode]);
        await f.list.load();
        expect(f.list.items.single.balance, isNull);
        f.transport.calls.clear();
        await f.account.load();
        expect(f.transport.calls, isEmpty);
        f.list.setMode(PartyMode.outstanding);
        f.list.setSort(PartySort.balance);
        expect(f.list.mode, PartyMode.all);
        expect(f.list.sort, PartySort.name);
        final restricted = await setup(kind, restricted: true);
        restricted.transport.calls.clear();
        await Future.wait([
          restricted.list.load(),
          restricted.detail.load(),
          restricted.account.load(),
        ]);
        expect(restricted.transport.calls, isEmpty);
      },
    );
  }
  test('central 401 refresh retries once and terminal rejection clears every parties controller', () async {
    final f = await setup(PartyKind.customer);
    var attempts = 0;
    f.transport.handler = (_, p, _, _) async {
      if (p.startsWith('manager/customers') && attempts++ == 0) {
        throw AppFailure.signedOut;
      }
      if (p == 'auth/refresh') {
        return {
          ...loginJson(access: 'rotated-fixture'),
          'user': userJson(permissions: partyPermissions),
        };
      }
      return partyResponse(p);
    };
    await f.list.load();
    expect(f.list.items, isNotEmpty);
    expect(attempts, 2);
    expect(f.transport.calls.where((c) => c.path == 'auth/refresh').length, 1);
    await Future.wait([f.detail.load(), f.account.load()]);
    f.transport.calls.clear();
    f.transport.handler = (_, _, _, _) async => throw AppFailure.signedOut;
    await f.list.load();
    expect(f.auth.status, AuthStatus.signedOut);
    expect(f.list.items, isEmpty);
    expect(f.detail.profile, isNull);
    expect(f.account.account, isNull);
    expect(f.transport.calls.where((c) => c.path == 'auth/refresh').length, 1);
  });
}
