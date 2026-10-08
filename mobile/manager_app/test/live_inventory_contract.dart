// Explicit opt-in; only the established disposable fixture may supply credentials.
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:manager_app/core/config/app_config.dart';
import 'package:manager_app/core/errors/app_failure.dart';
import 'package:manager_app/core/network/api_transport.dart';
import 'package:manager_app/features/auth/data/auth_repository.dart';
import 'package:manager_app/features/auth/state/auth_controller.dart';
import 'package:manager_app/features/inventory/data/inventory_access.dart';
import 'package:manager_app/features/inventory/data/inventory_models.dart';
import 'package:manager_app/features/inventory/data/inventory_repository.dart';
import 'package:manager_app/features/inventory/state/inventory_controller.dart';

import 'support/fakes.dart';

class InventoryObservedTransport implements ApiTransport {
  InventoryObservedTransport(this.inner);
  final ApiTransport inner;
  final responses = <({String method, String path, Object? body})>[];
  final cache = <String?>[];
  @override
  Future<Object?> send(
    String method,
    String path, {
    Object? body,
    String? accessToken,
  }) async {
    final data = await inner.send(
      method,
      path,
      body: body,
      accessToken: accessToken,
    );
    // Business responses are retained only in test memory. Never dump them or tokens.
    if (path.startsWith('products') || path.startsWith('manager/')) {
      responses.add((method: method, path: path, body: data));
    }
    return data;
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
  ({AuthController auth, InventoryObservedTransport transport}) create() {
    final dio = Dio(),
        transport = InventoryObservedTransport(
          DioApiTransport(
            AppConfig(url, allowHttp: true, release: false),
            dio: dio,
          ),
        );
    dio.interceptors.add(
      InterceptorsWrapper(
        onResponse: (response, handler) {
          if (response.statusCode == 200 &&
              (response.requestOptions.path.startsWith('products') ||
                  response.requestOptions.path.startsWith('manager/'))) {
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

  for (final role in ['ADMIN', 'ACCOUNTANT', 'CASHIER', 'STOREKEEPER']) {
    test(
      'live $role frozen inventory endpoints exact values pagination and redaction',
      () async {
        final f = create(), auth = f.auth;
        try {
          expect(
            await auth.login('p3_$role', password),
            isTrue,
            reason: auth.error?.code,
          );
          final access = InventoryAccess(auth.user),
              repo = InventoryRepository(auth.repository.client);
          expect(access.products, isTrue);
          final list = await repo.products(
            access,
            size: 1,
            search: 'P3-',
            sort: InventorySort.code,
          );
          expect(list.total, 3);
          expect(list.items.single.code, 'P3-A');
          expect(list.items.single.quantity, '2.125');
          expect(list.items.single.cost, access.cost ? '9.999' : null);
          final next = await repo.products(
            access,
            size: 1,
            page: 1,
            search: 'P3-',
            sort: InventorySort.code,
          );
          expect(next.items.single.code, 'P3-B');
          final empty = await repo.products(
            access,
            page: 10,
            size: 1,
            search: 'P3-',
          );
          expect(empty.items, isEmpty);
          final barcode = await repo.products(access, search: '001234567891');
          expect(barcode.items.single.id, list.items.single.id);
          final literal = await repo.products(
            access,
            search: "100%_[ O'Reilly",
          );
          expect(literal.items.single.id, list.items.single.id);
          final detail = await repo.detail(access, list.items.single.id);
          expect(detail.barcode, '001234567891');
          expect(detail.quantity, '2.125');
          expect(detail.lowStock, isTrue);
          expect(detail.cost, access.cost ? '9.999' : null);
          if (access.inactive) {
            final inactive = await repo.products(
              access,
              search: 'P3-',
              includeInactive: true,
            );
            expect(inactive.total, 4);
          }
          if (access.inventory) {
            final low = await repo.products(
              access,
              mode: InventoryMode.low,
              size: 1,
              sort: InventorySort.quantity,
            );
            expect(low.total, 2);
            expect(low.items.single.code, 'P3-B');
            final lowBarcode = await repo.products(
              access,
              mode: InventoryMode.low,
              search: '001234567891',
            );
            expect(lowBarcode.items.single.code, 'P3-A');
            final range = MovementRange.custom('2026-09-30', '2026-10-07');
            final moves = await repo.movements(
              access,
              detail.id,
              range,
              size: 2,
            );
            expect(moves.total, 5);
            expect(moves.items.first.quantity, '-2.125');
            expect(moves.items.first.before, '4.250');
            expect(moves.items.first.after, '2.125');
            expect(moves.items.first.unitCost, access.cost ? '1.250' : null);
            final page2 = await repo.movements(
              access,
              detail.id,
              range,
              size: 2,
              page: 1,
            );
            expect(page2.items.length, 2);
          } else {
            await expectLater(
              repo.products(access, mode: InventoryMode.low),
              throwsA(isA<AppFailure>()),
            );
            // Verify backend authorization independently of client permission checks.
            await expectLater(
              auth.repository.client.send('GET', 'manager/inventory/low-stock'),
              throwsA(isA<AppFailure>().having((e) => e.status, 'status', 403)),
            );
          }
          for (final sort in InventorySort.values) {
            await repo.products(access, search: 'P3-', sort: sort);
          }
          expect(f.transport.responses.every((c) => c.method == 'GET'), isTrue);
          expect(f.transport.cache.length, f.transport.responses.length);
          expect(
            f.transport.cache.every((c) => c?.contains('no-store') == true),
            isTrue,
          );
          if (!access.cost) {
            for (final response in f.transport.responses) {
              final map = response.body as Map;
              final rows = map['items'] is List ? map['items'] as List : [map];
              expect(
                rows.every(
                  (raw) =>
                      raw is Map &&
                      !raw.containsKey('purchasePrice') &&
                      !raw.containsKey('purchaseCost') &&
                      !raw.containsKey('unitCost'),
                ),
                isTrue,
              );
            }
          }
        } finally {
          await auth.logout();
          auth.dispose();
        }
      },
      timeout: const Timeout(Duration(minutes: 2)),
    );
  }
  test(
    'live restricted session blocked before inventory request and by backend',
    () async {
      final f = create();
      final state = InventoryListController(
        f.auth,
        InventoryRepository(f.auth.repository.client),
      );
      try {
        expect(await f.auth.login('flutter_restricted', password), isTrue);
        expect(f.auth.status, AuthStatus.restricted);
        await state.load();
        expect(state.items, isEmpty);
        expect(f.transport.responses, isEmpty);
        await expectLater(
          f.auth.repository.client.send('GET', 'products'),
          throwsA(isA<AppFailure>()),
        );
      } finally {
        state.dispose();
        await f.auth.logout();
        f.auth.dispose();
      }
    },
  );
  test(
    'live revoked session clears cached inventory through centralized auth',
    () async {
      final f = create(), second = create();
      final state = InventoryListController(
        f.auth,
        InventoryRepository(f.auth.repository.client),
      );
      try {
        expect(await f.auth.login('p3_ADMIN', password), isTrue);
        expect(await second.auth.login('p3_ADMIN', password), isTrue);
        await state.load();
        expect(state.items, isNotEmpty);
        await second.auth.repository.revoke(f.auth.sid!);
        await state.load();
        expect(state.items, isEmpty);
        expect(f.auth.status, AuthStatus.signedOut);
      } finally {
        state.dispose();
        await f.auth.logout();
        await second.auth.logout();
        f.auth.dispose();
        second.auth.dispose();
      }
    },
  );
}
