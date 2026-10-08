import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:manager_app/core/errors/app_failure.dart';
import 'package:manager_app/features/auth/state/auth_controller.dart';
import 'package:manager_app/features/inventory/data/inventory_models.dart';
import 'package:manager_app/features/inventory/data/inventory_repository.dart';
import 'package:manager_app/features/inventory/state/inventory_controller.dart';

import 'support/inventory_fixtures.dart';
import 'support/fakes.dart';

void main() {
  Future<
    ({
      InventoryListController state,
      AuthController auth,
      FakeTransport transport,
    })
  >
  setup({
    List<String> permissions = inventoryPermissions,
    bool restricted = false,
  }) async {
    final f = await inventoryAuth(
      permissions: permissions,
      restricted: restricted,
    );
    final state = InventoryListController(
      f.auth,
      InventoryRepository(f.auth.repository.client),
      size: 2,
    );
    addTearDown(() {
      state.dispose();
      f.auth.dispose();
    });
    return (state: state, auth: f.auth, transport: f.transport);
  }

  test('initial load has no invented data; refresh singleflight retains valid rows', () async {
    final f = await setup(), gate = Completer<Object?>();
    f.transport.handler = (_, _, _, _) => gate.future;
    final pending = f.state.load();
    expect(f.state.loading, isTrue);
    expect(f.state.items, isEmpty);
    expect(f.state.load(), same(pending));
    gate.complete(
      inventoryPage([productJson(1), productJson(2)], size: 2, total: 3),
    );
    await pending;
    expect(f.state.page, 0);
    expect(f.state.total, 3);
    expect(f.state.hasMore, isTrue);
    final next = Completer<Object?>();
    f.transport.handler = (_, _, _, _) => next.future;
    final refresh = f.state.load();
    expect(f.state.items.length, 2);
    next.complete(inventoryPage([productJson(1)], size: 2));
    await refresh;
    expect(f.state.items.length, 1);
    expect(f.state.hasMore, isFalse);
  });
  test(
    'initial failure and retry; search failure keeps explicit previous context',
    () async {
      final f = await setup();
      f.transport.handler = (_, _, _, _) async =>
          throw AppFailure.http(503, null);
      await f.state.load();
      expect(f.state.items, isEmpty);
      expect(f.state.error!.status, 503);
      f.transport.handler = (_, p, _, _) async => inventoryResponse(p);
      await f.state.load();
      final previous = f.state.items, label = f.state.loadedContext;
      f.transport.handler = (_, _, _, _) async =>
          throw AppFailure.http(503, null);
      f.state.setSearch('missing', immediate: true);
      await f.state.load();
      expect(f.state.items, same(previous));
      expect(f.state.loadedContext, label);
      expect(f.state.canLoadMore, isFalse);
    },
  );
  test(
    'new search wins even when old response finishes last; clear resets page',
    () async {
      final f = await setup(), old = Completer<Object?>();
      f.transport.handler = (_, p, _, _) =>
          Uri.parse(p).queryParameters['q'] == 'old'
          ? old.future
          : Future.value(inventoryPage([productJson(2)], size: 2));
      f.state.setSearch('old', immediate: true);
      final pending = f.state.load();
      f.state.setSearch('new', immediate: true);
      await f.state.load();
      old.complete(inventoryPage([productJson(1)], size: 2));
      await pending;
      expect(f.state.items.single.id, 2);
      expect(f.state.loadedContext, contains('new'));
      f.state.setSearch('');
      await f.state.load();
      expect(f.state.page, 0);
      expect(
        Uri.parse(f.transport.calls.last.path).queryParameters.containsKey('q'),
        isFalse,
      );
    },
  );
  test('debounce suppresses transient searches and trimming avoids duplicate fetches', () async {
    final f = await setup();
    await f.state.load();
    f.transport.calls.clear();
    f.state.setSearch(' x ');
    f.state.setSearch(' xy ');
    f.state.setSearch('xyz');
    expect(f.state.searching, isTrue);
    expect(f.transport.calls, isEmpty);
    await Future<void>.delayed(const Duration(milliseconds: 380));
    expect(
      f.transport.calls
          .where((c) => Uri.parse(c.path).queryParameters['q'] == 'xyz')
          .length,
      1,
    );
    final count = f.transport.calls.length;
    f.state.setSearch(' xyz ');
    expect(f.transport.calls.length, count);
  });
  test(
    'filter and sort changes reset to page zero and discard pending old mode',
    () async {
      final f = await setup(), old = Completer<Object?>();
      f.transport.handler = (_, p, _, _) => p.startsWith('products')
          ? old.future
          : Future.value(inventoryResponse(p));
      final pending = f.state.load();
      f.state.setMode(InventoryMode.low);
      await f.state.load();
      old.complete(inventoryPage([productJson(3)], size: 2));
      await pending;
      expect(f.state.mode, InventoryMode.low);
      expect(f.state.items.every((p) => p.lowStock), isTrue);
      f.state.setSort(InventorySort.code);
      await f.state.load();
      expect(f.state.page, 0);
      expect(
        Uri.parse(f.transport.calls.last.path).queryParameters['sort'],
        'code,asc',
      );
    },
  );
  test('next page duplicates suppressed; retry requests same failed page; end stable', () async {
    final f = await setup();
    await f.state.load();
    final gate = Completer<Object?>();
    f.transport.handler = (_, _, _, _) => gate.future;
    final pending = f.state.nextPage();
    expect(f.state.nextPage(), same(pending));
    expect(f.state.loadingMore, isTrue);
    gate.completeError(AppFailure.http(503, null));
    await pending;
    expect(f.state.page, 0);
    expect(f.state.items.length, 2);
    expect(f.state.nextError, isNotNull);
    f.transport.handler = (_, p, _, _) async => inventoryResponse(p);
    await f.state.nextPage();
    expect(f.state.page, 1);
    expect(f.state.items.map((p) => p.id), [1, 2, 3]);
    expect(f.state.hasMore, isFalse);
    final count = f.transport.calls.length;
    await f.state.nextPage();
    expect(f.transport.calls.length, count);
    expect(
      f.transport.calls
          .where((c) => Uri.parse(c.path).queryParameters['page'] == '1')
          .length,
      2,
    );
  });
  test(
    'refresh supersedes in-flight next page without appending old rows',
    () async {
      final f = await setup();
      await f.state.load();
      final old = Completer<Object?>();
      f.transport.handler = (_, p, _, _) =>
          Uri.parse(p).queryParameters['page'] == '1'
          ? old.future
          : Future.value(inventoryPage([productJson(2)], size: 2));
      final pending = f.state.nextPage();
      await f.state.load();
      old.complete(inventoryPage([productJson(3)], page: 1, size: 2, total: 3));
      await pending;
      expect(f.state.items.map((p) => p.id), [2]);
      expect(f.state.page, 0);
    },
  );
  test('overlapping product page does not duplicate identity; empty next page stops', () async {
    final f = await setup();
    await f.state.load();
    f.transport.handler = (_, _, _, _) async => inventoryPage(
      [productJson(2), productJson(3)],
      page: 1,
      size: 2,
      total: 6,
    );
    await f.state.nextPage();
    expect(f.state.items.map((p) => p.id), [1, 2, 3]);
    f.transport.handler = (_, _, _, _) async =>
        inventoryPage([], page: 2, size: 2, total: 6);
    await f.state.nextPage();
    expect(f.state.hasMore, isFalse);
  });
  test('product detail identity change discards late old detail', () async {
    final f = await setup(), old = Completer<Object?>();
    final state = ProductDetailController(
      f.auth,
      InventoryRepository(f.auth.repository.client),
      1,
    );
    addTearDown(state.dispose);
    f.transport.handler = (_, p, _, _) =>
        p.endsWith('/1') ? old.future : Future.value(detailJson(2));
    final pending = state.load();
    await state.selectProduct(2);
    old.complete(detailJson(1));
    await pending;
    expect(state.product!.id, 2);
  });
  test(
    'detail refresh retains safe data on network error but clears on not found',
    () async {
      final f = await setup();
      final state = ProductDetailController(
        f.auth,
        InventoryRepository(f.auth.repository.client),
        1,
      );
      addTearDown(state.dispose);
      await state.load();
      f.transport.handler = (_, _, _, _) async =>
          throw AppFailure.http(503, null);
      await state.load();
      expect(state.product, isNotNull);
      f.transport.handler = (_, _, _, _) async =>
          throw AppFailure.http(404, null);
      await state.load();
      expect(state.product, isNull);
    },
  );
  test(
    'movement signed quantities and identical legitimate events survive pages',
    () async {
      final f = await setup();
      final state = MovementController(
        f.auth,
        InventoryRepository(f.auth.repository.client),
        1,
        size: 2,
      );
      addTearDown(state.dispose);
      await state.load();
      await state.nextPage();
      expect(state.items.length, 3);
      expect(state.items.every((m) => m.quantity == '-2.125'), isTrue);
      state.setRange(MovementRange.custom('2026-09-01', '2026-09-30'));
      await state.load();
      expect(state.page, 0);
      expect(
        Uri.parse(f.transport.calls.last.path).queryParameters['from'],
        '2026-09-01',
      );
    },
  );
  test('cost downgrade clears all protected models before pending reload and drops over-return', () async {
    final f = await setup();
    final repo = InventoryRepository(f.auth.repository.client),
        detail = ProductDetailController(
          f.auth,
          InventoryRepository(f.auth.repository.client),
          1,
        ),
        moves = MovementController(
          f.auth,
          InventoryRepository(f.auth.repository.client),
          1,
        );
    addTearDown(detail.dispose);
    addTearDown(moves.dispose);
    await Future.wait([f.state.load(), detail.load(), moves.load()]);
    expect(f.state.items.first.cost, isNotNull);
    final permissions = inventoryPermissions
            .where((p) => p != 'PRODUCT_COST')
            .toList(),
        gate = Completer<Object?>();
    f.transport.handler = (_, p, _, _) => p == 'auth/me'
        ? Future.value(userJson(permissions: permissions))
        : gate.future;
    await f.auth.revalidate();
    expect(f.state.items, isEmpty);
    expect(detail.product, isNull);
    expect(moves.items, isEmpty);
    // Finish each distinct request with the corresponding response.
    f.transport.handler = (_, p, _, _) async => inventoryResponse(p);
    f.state.queryChanged();
    await f.state.load();
    await detail.selectProduct(2);
    moves.queryChanged();
    await moves.load();
    expect(f.state.items.every((p) => p.cost == null), isTrue);
    expect(detail.product!.cost, isNull);
    expect(moves.items.every((m) => m.unitCost == null), isTrue);
    expect(repo.toString(), isNot(contains('777.987')));
    gate.complete(null);
  });
  test(
    'late privileged request cannot republish after permission loss',
    () async {
      final f = await setup(), old = Completer<Object?>();
      f.transport.handler = (_, p, _, _) =>
          p == 'auth/me' ? Future.value(userJson(permissions: [])) : old.future;
      final pending = f.state.load();
      await f.auth.revalidate();
      old.complete(inventoryPage([productJson(1)], size: 2));
      await pending;
      expect(f.state.allowed, isFalse);
      expect(f.state.items, isEmpty);
    },
  );
  for (final restricted in [false, true]) {
    test(
      'unauthorized/restricted=$restricted never requests business data',
      () async {
        final f = await setup(
          permissions: restricted ? inventoryPermissions : [],
          restricted: restricted,
        );
        await f.state.load();
        expect(f.state.allowed, isFalse);
        expect(
          f.transport.calls.where(
            (c) =>
                c.path.startsWith('products') || c.path.startsWith('manager/'),
          ),
          isEmpty,
        );
      },
    );
  }
  test('live me password restriction invalidates loaded inventory without another business call', () async {
    final f = await setup();
    await f.state.load();
    final count = f.transport.calls
        .where((c) => c.path.startsWith('products'))
        .length;
    f.transport.handler = (_, p, _, _) async => p == 'auth/me'
        ? userJson(permissions: inventoryPermissions, restricted: true)
        : inventoryResponse(p);
    await f.auth.revalidate();
    expect(f.auth.status, AuthStatus.restricted);
    expect(f.state.items, isEmpty);
    expect(f.state.allowed, isFalse);
    expect(
      f.transport.calls.where((c) => c.path.startsWith('products')).length,
      count,
    );
  });
  test('logout invalidates pending request and private data', () async {
    final f = await setup(), old = Completer<Object?>();
    f.transport.handler = (_, _, _, _) => old.future;
    final pending = f.state.load();
    await f.auth.terminate();
    old.complete(inventoryPage([productJson(1)], size: 2));
    await pending;
    expect(f.state.items, isEmpty);
    expect(f.auth.status, AuthStatus.signedOut);
  });
  test(
    '403 clears before slow me completes; no blind business retry loop',
    () async {
      final f = await setup();
      await f.state.load();
      final me = Completer<Object?>();
      var sawClear = false;
      f.state.addListener(() {
        if (f.state.error?.status == 403 && f.state.items.isEmpty) {
          sawClear = true;
        }
      });
      f.transport.handler = (_, p, _, _) =>
          p == 'auth/me' ? me.future : Future.error(AppFailure.http(403, null));
      final pending = f.state.load();
      await Future<void>.delayed(Duration.zero);
      expect(sawClear, isTrue);
      expect(f.state.items, isEmpty);
      me.complete(userJson(permissions: inventoryPermissions));
      await pending;
      expect(f.state.error!.status, 403);
    },
  );
  test('401 recovers only through existing refresh once', () async {
    final f = await setup();
    var attempts = 0;
    f.transport.handler = (_, p, _, _) async {
      if (p.startsWith('products') && attempts++ == 0) {
        throw AppFailure.signedOut;
      }
      if (p == 'auth/refresh') {
        return {
          ...loginJson(access: 'rotated-fixture'),
          'user': userJson(permissions: inventoryPermissions),
        };
      }
      return inventoryResponse(p);
    };
    await f.state.load();
    expect(f.state.items, isNotEmpty);
    expect(attempts, 2);
    expect(f.transport.calls.where((c) => c.path == 'auth/refresh').length, 1);
  });
  test('terminal 401 clears state via inherited centralized signout', () async {
    final f = await setup();
    await f.state.load();
    f.transport.handler = (_, _, _, _) async => throw AppFailure.signedOut;
    await f.state.load();
    expect(f.auth.status, AuthStatus.signedOut);
    expect(f.state.items, isEmpty);
    expect(f.transport.calls.where((c) => c.path == 'auth/refresh').length, 1);
  });
  test(
    'removing inactive permission resets active filter immediately',
    () async {
      final f = await setup();
      await f.state.load();
      f.state.setInactive(true);
      await f.state.load();
      f.transport.handler = (_, p, _, _) async => p == 'auth/me'
          ? userJson(
              permissions: inventoryPermissions
                  .where((p) => p != 'PRODUCTS')
                  .toList(),
            )
          : inventoryResponse(p);
      await f.auth.revalidate();
      await f.state.load();
      expect(f.state.includeInactive, isFalse);
      expect(
        Uri.parse(f.transport.calls.last.path)
            .queryParameters['includeInactive'],
        'false',
      );
    },
  );
}
