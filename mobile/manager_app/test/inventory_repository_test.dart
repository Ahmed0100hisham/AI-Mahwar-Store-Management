import 'package:flutter_test/flutter_test.dart';
import 'package:manager_app/core/errors/app_failure.dart';
import 'package:manager_app/core/utils/money.dart';
import 'package:manager_app/features/auth/data/auth_models.dart';
import 'package:manager_app/features/inventory/data/inventory_access.dart';
import 'package:manager_app/features/inventory/data/inventory_models.dart';
import 'package:manager_app/features/inventory/data/inventory_repository.dart';

import 'support/fakes.dart';
import 'support/inventory_fixtures.dart';

void main() {
  InventoryAccess access([List<String> permissions = inventoryPermissions]) =>
      InventoryAccess(CurrentUser.fromJson(userJson(permissions: permissions)));
  test('valid list, detail and low-stock map distinct frozen cost keys', () {
    final list = InventoryProduct.fromJson(productJson(), access());
    final low = InventoryProduct.fromJson(lowJson(), access(), low: true);
    final detail = InventoryProduct.fromJson(
      detailJson(),
      access(),
      detail: true,
    );
    expect(list.cost, '777.987');
    expect(low.cost, '777.987');
    expect(detail.cost, '777.987');
    expect(list.barcode, '001234567891');
    expect(low.barcode, isNull);
    expect(detail.wholesalePrice, isNull);
    expect(detail.lowStock, isTrue);
    expect(list.stockLabel, 'مخزون منخفض');
  });
  for (final value in ['0.000', '-2.125', '1.125', '9007199254740993.125']) {
    test('exact quantity and money preserved: $value', () {
      final p = InventoryProduct.fromJson({
        ...productJson(),
        'quantity': value,
        'salePrice': value,
      }, access());
      expect(p.quantity, value);
      expect(p.salePrice, value);
      expect(formatQuantity(value).replaceAll(',', ''), value);
      expect(formatKwd(value), '${formatQuantity(value)} د.ك');
    });
  }
  test(
    'stock status follows released active thresholds with exact comparison',
    () {
      expect(
        InventoryProduct.fromJson(productJson(2), access()).stockLabel,
        'نفد المخزون',
      );
      expect(
        InventoryProduct.fromJson(productJson(3), access()).stockLabel,
        'متوفر',
      );
      expect(
        InventoryProduct.fromJson({
          ...productJson(),
          'active': false,
        }, access()).stockLabel,
        'غير نشط',
      );
      expect(
        InventoryProduct.fromJson(
          {...detailJson(), 'lowStock': false},
          access(),
          detail: true,
        ).lowStock,
        isFalse,
      );
    },
  );
  test('over-returned unauthorized cost is discarded from every DTO and diagnostics', () {
    final noCost = access([
      'PRODUCTS_VIEW',
      'INVENTORY',
      'REPORTS_VIEW',
      'REPORTS_INVENTORY',
    ]);
    for (final options in [
      (productJson(), false, false),
      (lowJson(), true, false),
      (detailJson(), false, true),
    ]) {
      final p = InventoryProduct.fromJson(
        options.$1,
        noCost,
        low: options.$2,
        detail: options.$3,
      );
      expect(p.cost, isNull);
      expect(p.toString(), isNot(contains('777.987')));
    }
    final movement = InventoryMovement.fromJson(movementJson(), noCost);
    expect(movement.unitCost, isNull);
    expect(movement.toString(), isNot(contains('778.987')));
  });
  test(
    'missing authorized cost stays absent, present malformed cost fails',
    () {
      final raw = productJson()..remove('purchasePrice');
      expect(InventoryProduct.fromJson(raw, access()).cost, isNull);
      for (final bad in [null, 0, '1.00', 'secret']) {
        expect(
          () => InventoryProduct.fromJson({
            ...productJson(),
            'purchasePrice': bad,
          }, access()),
          throwsA(AppFailure.malformed),
        );
      }
      expect(
        InventoryMovement.fromJson(
          movementJson()..remove('unitCost'),
          access(),
        ).unitCost,
        isNull,
      );
    },
  );
  for (final field in [
    'id',
    'code',
    'nameAr',
    'unit',
    'quantity',
    'minimumStock',
    'salePrice',
    'wholesalePrice',
    'active',
  ]) {
    test('malformed required product field $field fails safely', () {
      final raw = productJson()..remove(field);
      expect(
        () => InventoryProduct.fromJson(raw, access()),
        throwsA(AppFailure.malformed),
      );
    });
  }
  test('nullable product metadata accepts null but rejects wrong types', () {
    expect(InventoryProduct.fromJson(productJson(), access()).brand, isNull);
    expect(
      () => InventoryProduct.fromJson({
        ...productJson(),
        'barcode': 123,
      }, access()),
      throwsA(AppFailure.malformed),
    );
  });
  test('signed movements, exact historical cost and future movement types are retained', () {
    final m = InventoryMovement.fromJson({
      ...movementJson(),
      'type': 'FUTURE_TYPE',
      'typeName': 'حركة جديدة',
    }, access());
    expect(m.quantity, '-2.125');
    expect(m.before, '6.250');
    expect(m.after, '4.125');
    expect(m.unitCost, '778.987');
    expect(m.type, 'FUTURE_TYPE');
  });
  for (final bad in [
    '2026-02-30T00:00:00',
    '2026-10-07T25:00:00',
    '2026-10-07T00:00:00Z',
    '2026-10-07T00:00:00+03:00',
    'raw',
  ]) {
    test('movement local timestamp rejects malformed/offset value $bad', () {
      expect(
        () => InventoryMovement.fromJson({
          ...movementJson(),
          'date': bad,
        }, access()),
        throwsA(AppFailure.malformed),
      );
    });
  }
  test('movement missing quantity and malformed present cost fail', () {
    expect(
      () =>
          InventoryMovement.fromJson(movementJson()..remove('after'), access()),
      throwsA(AppFailure.malformed),
    );
    expect(
      () => InventoryMovement.fromJson({
        ...movementJson(),
        'unitCost': null,
      }, access()),
      throwsA(AppFailure.malformed),
    );
  });
  test('empty page and beyond-end page are legitimate', () {
    final page = InventoryPage<InventoryProduct>.fromJson(
      inventoryPage([], page: 9, total: 1),
      (raw) => InventoryProduct.fromJson(raw, access()),
      expectedPage: 9,
      expectedSize: 20,
    );
    expect(page.items, isEmpty);
    expect(page.pages, 1);
  });
  for (final change in [
    <String, Object?>{'page': 1},
    {'size': 0},
    {'totalItems': -1},
    {'totalPages': 10},
    {'items': null},
  ]) {
    test('malformed pagination metadata $change fails', () {
      expect(
        () => InventoryPage<InventoryProduct>.fromJson(
          {...inventoryPage([]), ...change},
          (raw) => InventoryProduct.fromJson(raw, access()),
          expectedPage: 0,
          expectedSize: 20,
        ),
        throwsA(AppFailure.malformed),
      );
    });
  }
  test(
    'permission intersections use codes regardless of CASHIER role label',
    () {
      expect(access().enter, isTrue);
      expect(access(['INVENTORY']).enter, isFalse);
      expect(access(['PRODUCTS_VIEW']).products, isTrue);
      expect(
        access(['INVENTORY', 'REPORTS_VIEW', 'REPORTS_INVENTORY']).inventory,
        isTrue,
      );
      expect(
        access(['INVENTORY', 'REPORTS_VIEW', 'REPORTS_INVENTORY']).movements,
        isFalse,
      );
    },
  );
  test('search including literal barcode/wildcards is safely URI encoded; all sorts fixed', () async {
    final fixture = await inventoryAuth();
    addTearDown(fixture.auth.dispose);
    final repository = InventoryRepository(fixture.auth.repository.client);
    for (final search in [' 001234567891 ', 'عربي 100%_[ & + # O\'Reilly']) {
      for (final sort in InventorySort.values) {
        await repository.products(access(), search: search, sort: sort);
        final call = fixture.transport.calls.last,
            uri = Uri.parse(fixture.transport.calls.last.path);
        expect(call.method, 'GET');
        expect(uri.path, 'products');
        expect(uri.queryParameters['q'], search.trim());
        expect(uri.queryParameters['sort'], sort.api);
      }
    }
    await repository.products(access(), mode: InventoryMode.low);
    expect(
      Uri.parse(fixture.transport.calls.last.path).path,
      'manager/inventory/low-stock',
    );
  });
  test('detail ID mismatch and duplicate product IDs fail closed', () async {
    final fixture = await inventoryAuth();
    addTearDown(fixture.auth.dispose);
    fixture.transport.handler = (_, path, _, _) async =>
        path.startsWith('manager/products/')
        ? detailJson(2)
        : inventoryPage([productJson(), productJson()]);
    final repo = InventoryRepository(fixture.auth.repository.client);
    await expectLater(repo.detail(access(), 1), throwsA(AppFailure.malformed));
    await expectLater(repo.products(access()), throwsA(AppFailure.malformed));
  });
  test(
    'page/range bounds and unauthorized calls are rejected before transport',
    () async {
      final fixture = await inventoryAuth();
      addTearDown(fixture.auth.dispose);
      final repo = InventoryRepository(fixture.auth.repository.client),
          before = fixture.transport.calls.length;
      await expectLater(
        repo.products(access(), page: 10001),
        throwsA(AppFailure.malformed),
      );
      await expectLater(
        repo.products(access(), search: 'x' * 101),
        throwsA(isA<AppFailure>()),
      );
      await expectLater(
        repo.products(access(['PRODUCTS_VIEW']), mode: InventoryMode.low),
        throwsA(isA<AppFailure>()),
      );
      await expectLater(
        repo.products(access(['PRODUCTS_VIEW']), includeInactive: true),
        throwsA(isA<AppFailure>()),
      );
      expect(fixture.transport.calls.length, before);
      expect(
        () => MovementRange.custom('2026-10-08', '2026-10-07'),
        throwsA(isA<AppFailure>()),
      );
      expect(
        () => MovementRange.custom('2020-01-01', '2026-10-07'),
        throwsA(isA<AppFailure>()),
      );
    },
  );
  test('movement query uses server presets or validated custom range and fixed date desc', () async {
    final fixture = await inventoryAuth();
    addTearDown(fixture.auth.dispose);
    final repo = InventoryRepository(fixture.auth.repository.client);
    for (final range in [
      const MovementRange.preset(MovementPeriod.month),
      MovementRange.custom('2026-10-01', '2026-10-07'),
    ]) {
      final rows = await repo.movements(access(), 1, range, size: 1, page: 1);
      final query = Uri.parse(fixture.transport.calls.last.path)
          .queryParameters;
      expect(query['sort'], 'date,desc');
      expect(query['q'], isNull);
      expect(query['page'], '1');
      expect(rows.items.single.quantity, '-2.125');
    }
  });
  test(
    'safe API failure propagates without raw internals or request retry',
    () async {
      final fixture = await inventoryAuth();
      addTearDown(fixture.auth.dispose);
      fixture.transport.handler = (_, _, _, _) async =>
          throw AppFailure.http(503, {'message': 'private SQL traceback'});
      final before = fixture.transport.calls.length;
      await expectLater(
        InventoryRepository(fixture.auth.repository.client).products(access()),
        throwsA(
          isA<AppFailure>().having(
            (e) => e.message,
            'safe error',
            isNot(contains('SQL')),
          ),
        ),
      );
      expect(fixture.transport.calls.length, before + 1);
    },
  );
}
