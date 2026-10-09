import 'package:flutter_test/flutter_test.dart';
import 'package:manager_app/core/errors/app_failure.dart';
import 'package:manager_app/core/utils/money.dart';
import 'package:manager_app/features/auth/data/auth_models.dart';
import 'package:manager_app/features/parties/data/party_access.dart';
import 'package:manager_app/features/parties/data/party_models.dart';
import 'package:manager_app/features/parties/data/party_repository.dart';

import 'support/fakes.dart';
import 'support/inventory_fixtures.dart';
import 'support/party_fixtures.dart';

void main() {
  PartyAccess access(PartyKind kind, [List<String> codes = partyPermissions]) =>
      PartyAccess(CurrentUser.fromJson(userJson(permissions: codes)), kind);
  const month = PartyRange.preset(PartyPeriod.month);
  for (final kind in PartyKind.values) {
    final a = access(kind);
    test(
      '${kind.name}: identity, balance and other party codes are independent of roles',
      () {
        expect(access(kind, []).identity, isFalse);
        expect(access(kind, [kind.balanceCode]).financial, isFalse);
        expect(access(kind, [kind.identityCode]).identity, isTrue);
        expect(access(kind, [kind.identityCode]).financial, isFalse);
        expect(
          access(kind, [kind.identityCode, kind.balanceCode]).financial,
          isTrue,
        );
        final other = kind == PartyKind.customer
            ? PartyKind.supplier
            : PartyKind.customer;
        expect(
          access(kind, [other.identityCode, other.balanceCode]).identity,
          isFalse,
        );
        expect(
          PartyAccess(
            CurrentUser.fromJson(
              userJson(permissions: partyPermissions, restricted: true),
            ),
            kind,
          ).identity,
          isFalse,
        );
      },
    );
    for (final value in ['0.000', '-2.125', '1.125', '9007199254740993.125']) {
      test('${kind.name}: exact signed profile and ledger decimals $value', () {
        final p = PartyProfile.fromJson({
          ...partyJson(kind, 1),
          'balance': value,
        }, a);
        final e = PartyEntry.fromJson({
          ...partyEntry(kind, 1),
          'debit': value,
          'credit': value,
          'runningBalance': value,
        });
        final raw = {
          ...partyAccountJson(kind),
          'openingBalance': value,
          'closingBalance': value,
        };
        final account = PartyAccount.fromJson(
          raw,
          a,
          id: 1,
          page: 0,
          size: 20,
          requested: month,
        );
        expect(p.balance, value);
        expect(e.debit, value);
        expect(e.credit, value);
        expect(e.runningBalance, value);
        expect(account.opening, value);
        expect(account.closing, value);
        expect(formatKwd(value).replaceAll(',', ''), '$value د.ك');
      });
    }
    test(
      '${kind.name}: missing permitted balance is absent and unauthorized malformed over-return is discarded',
      () {
        final raw = {...partyJson(kind, 1)}..remove('balance');
        expect(PartyProfile.fromJson(raw, a).balance, isNull);
        expect(
          PartyProfile.fromJson({
            ...raw,
            'balance': {'private': '777.987'},
          }, access(kind, [kind.identityCode])).balance,
          isNull,
        );
        expect(
          () => PartyAccount.fromJson(
            partyAccountJson(kind),
            access(kind, [kind.identityCode]),
            id: 1,
            page: 0,
            size: 20,
            requested: month,
          ),
          throwsA(isA<AppFailure>()),
        );
      },
    );
    test(
      '${kind.name}: profile details and privacy-safe diagnostic strings',
      () {
        final p = PartyProfile.fromJson(partyJson(kind, 2), a);
        expect(p.active, isFalse);
        expect(p.phone, isNull);
        expect(p.area, isNull);
        final account = PartyAccount.fromJson(
          partyAccountJson(kind),
          a,
          id: 1,
          page: 0,
          size: 20,
          requested: month,
        );
        for (final model in [p, account, account.entries.items.first]) {
          expect(model.toString(), contains('[PRIVATE]'));
          expect(model.toString(), isNot(contains('8.875')));
        }
      },
    );
    test(
      '${kind.name}: closing is full range, never first page running balance',
      () {
        final account = PartyAccount.fromJson(
          partyAccountJson(kind, size: 1),
          a,
          id: 1,
          page: 0,
          size: 1,
          requested: month,
        );
        expect(
          account.opening,
          kind == PartyKind.customer ? '5.000' : '12.000',
        );
        expect(account.closing, kind == PartyKind.customer ? '8.875' : '7.000');
        expect(
          account.entries.items.first.runningBalance,
          kind == PartyKind.customer ? '15.000' : '17.000',
        );
        expect(
          account.entries.items.first.runningBalance,
          isNot(account.closing),
        );
      },
    );
    test(
      '${kind.name}: wrapper aggregate covers all matches and regular list drops over-returned aggregate',
      () {
        final raw = {
          'page': inventoryPage([partyJson(kind, 2)], size: 1, total: 2),
          'totalOutstanding': kind == PartyKind.customer ? '28.875' : '11.000',
        };
        final result = PartyListing.fromJson(
          raw,
          a,
          page: 0,
          size: 1,
          mode: PartyMode.outstanding,
        );
        expect(result.totalOutstanding, raw['totalOutstanding']);
        expect(result.entries.total, 2);
        expect(
          PartyListing.fromJson(
            raw,
            a,
            page: 0,
            size: 1,
            mode: PartyMode.all,
          ).totalOutstanding,
          isNull,
        );
        expect(
          PartyListing.fromJson(
            {...raw, 'totalOutstanding': null},
            access(kind, [kind.identityCode]),
            page: 0,
            size: 1,
            mode: PartyMode.outstanding,
          ).totalOutstanding,
          isNull,
        );
      },
    );
    test('${kind.name}: empty list and statement are successful responses', () {
      final list = PartyListing.fromJson(
        {'page': inventoryPage([], total: 0)},
        a,
        page: 0,
        size: 20,
        mode: PartyMode.all,
      );
      expect(list.entries.items, isEmpty);
      expect(list.entries.pages, 0);
      final account = PartyAccount.fromJson(
        {...partyAccountJson(kind), 'entries': inventoryPage([], total: 0)},
        a,
        id: 1,
        page: 0,
        size: 20,
        requested: month,
      );
      expect(account.entries.items, isEmpty);
      expect(account.opening, isNotEmpty);
    });
    test(
      '${kind.name}: real GET list/debt/detail/account and literal search mapping',
      () async {
        final f = await partyAuth();
        addTearDown(f.auth.dispose);
        final r = PartyRepository(f.auth.repository.client);
        await r.list(
          a,
          search: "  خاص 100%_[ O'Reilly  ",
          page: 2,
          size: 1,
          sort: PartySort.codeDescending,
        );
        final call = f.transport.calls.last, uri = Uri.parse(call.path);
        expect(call.method, 'GET');
        expect(uri.path, 'manager/${kind.path}');
        expect(uri.queryParameters, {
          'page': '2',
          'size': '1',
          'sort': 'code,desc',
          'q': "خاص 100%_[ O'Reilly",
        });
        await r.list(a, mode: PartyMode.outstanding, size: 1);
        expect(
          Uri.parse(f.transport.calls.last.path).path,
          'manager/${kind.path}/${kind.debtPath}',
        );
        expect(
          Uri.parse(f.transport.calls.last.path).queryParameters['sort'],
          'balance,desc',
        );
        await r.detail(a, 1);
        expect(f.transport.calls.last.path, 'manager/${kind.path}/1');
        await r.account(
          a,
          1,
          PartyRange.custom('2026-10-01', '2026-10-07'),
          page: 1,
          size: 1,
        );
        expect(Uri.parse(f.transport.calls.last.path).queryParameters, {
          'period': 'custom',
          'from': '2026-10-01',
          'to': '2026-10-07',
          'page': '1',
          'size': '1',
          'sort': 'date,asc',
        });
        expect(
          f.transport.calls
              .where((c) => c.path.startsWith('manager/'))
              .every((c) => c.method == 'GET' && c.body == null),
          isTrue,
        );
      },
    );
    test(
      '${kind.name}: unauthorized endpoint and balance-sort requests never reach transport',
      () async {
        final f = await partyAuth();
        addTearDown(f.auth.dispose);
        final r = PartyRepository(f.auth.repository.client),
            identity = access(kind, [kind.identityCode]);
        final before = f.transport.calls.length;
        await expectLater(r.list(access(kind, [])), throwsA(isA<AppFailure>()));
        await expectLater(
          r.list(identity, mode: PartyMode.outstanding),
          throwsA(isA<AppFailure>()),
        );
        await expectLater(
          r.list(identity, sort: PartySort.balance),
          throwsA(isA<AppFailure>()),
        );
        await expectLater(
          r.account(identity, 1, month),
          throwsA(isA<AppFailure>()),
        );
        expect(f.transport.calls.length, before);
      },
    );
    test(
      '${kind.name}: wrong detail or account identity is rejected',
      () async {
        final f = await partyAuth();
        addTearDown(f.auth.dispose);
        final r = PartyRepository(f.auth.repository.client);
        f.transport.handler = (_, p, _, _) async =>
            p.endsWith('/account?') ? null : partyJson(kind, 2);
        await expectLater(r.detail(a, 1), throwsA(isA<AppFailure>()));
        expect(
          () => PartyAccount.fromJson(
            partyAccountJson(kind, id: 2),
            a,
            id: 1,
            page: 0,
            size: 20,
            requested: month,
          ),
          throwsA(isA<AppFailure>()),
        );
      },
    );
    for (final sort in PartySort.values) {
      test('${kind.name}: allowlisted sort ${sort.api}', () async {
        final f = await partyAuth();
        addTearDown(f.auth.dispose);
        await PartyRepository(f.auth.repository.client).list(a, sort: sort);
        expect(
          Uri.parse(f.transport.calls.last.path).queryParameters['sort'],
          sort.api,
        );
      });
    }
  }
  final a = access(PartyKind.customer);
  for (final field in [
    'id',
    'code',
    'name',
    'phone',
    'area',
    'active',
    'balance',
  ]) {
    test('malformed present profile field $field fails safely', () {
      final raw = {
        ...partyJson(PartyKind.customer, 1),
        field: field == 'id'
            ? 0
            : field == 'active'
            ? 'true'
            : field == 'balance'
            ? '1.00'
            : 42,
      };
      expect(() => PartyProfile.fromJson(raw, a), throwsA(isA<AppFailure>()));
    });
  }
  for (final value in [
    null,
    1.125,
    '1',
    '1.12',
    '1.1234',
    'NaN',
    '1e3',
    '+1.000',
  ]) {
    test('authorized malformed financial $value is not coerced', () {
      expect(
        () => PartyProfile.fromJson({
          ...partyJson(PartyKind.customer, 1),
          'balance': value,
        }, a),
        throwsA(isA<AppFailure>()),
      );
      expect(
        () => PartyEntry.fromJson({
          ...partyEntry(PartyKind.customer, 1),
          'debit': value,
        }),
        throwsA(isA<AppFailure>()),
      );
    });
  }
  for (final field in [
    'openingBalance',
    'totalDebit',
    'totalCredit',
    'closingBalance',
  ]) {
    test('malformed full-range $field fails', () {
      expect(
        () => PartyAccount.fromJson(
          {...partyAccountJson(PartyKind.customer), field: null},
          a,
          id: 1,
          page: 0,
          size: 20,
          requested: month,
        ),
        throwsA(isA<AppFailure>()),
      );
    });
  }
  for (final date in [
    '2026-02-30T12:00:00',
    '2026-10-01T24:00:00',
    '2026-10-01T12:60:00',
    '2026-10-01T12:00:60',
    '2026-10-01T12:00:00Z',
  ]) {
    test(
      'local ledger timestamp rejects $date',
      () => expect(
        () => PartyEntry.fromJson({
          ...partyEntry(PartyKind.customer, 1),
          'date': date,
        }),
        throwsA(isA<AppFailure>()),
      ),
    );
  }
  test('unknown future ledger type and nullable reference are retained without invented links', () {
    final e = PartyEntry.fromJson({
      ...partyEntry(PartyKind.customer, 1),
      'type': 'FUTURE_TYPE',
      'reference': null,
    });
    expect(accountTypeLabel(e.type), 'FUTURE_TYPE');
    expect(e.reference, isNull);
  });
  test(
    'statement ordering and requested dates validated; equal events retained',
    () {
      final raw = partyAccountJson(PartyKind.customer);
      final same = partyEntry(PartyKind.customer, 1);
      final equal = PartyAccount.fromJson(
        {
          ...raw,
          'entries': inventoryPage([same, same], total: 2),
        },
        a,
        id: 1,
        page: 0,
        size: 20,
        requested: month,
      );
      expect(equal.entries.items.length, 2);
      expect(
        () => PartyAccount.fromJson(
          {
            ...raw,
            'entries': inventoryPage([
              partyEntry(PartyKind.customer, 2),
              same,
            ], total: 2),
          },
          a,
          id: 1,
          page: 0,
          size: 20,
          requested: month,
        ),
        throwsA(isA<AppFailure>()),
      );
      expect(
        () => PartyAccount.fromJson(
          raw,
          a,
          id: 1,
          page: 0,
          size: 20,
          requested: PartyRange.custom('2026-10-02', '2026-10-31'),
        ),
        throwsA(isA<AppFailure>()),
      );
    },
  );
  for (final field in ['page', 'size', 'totalItems', 'totalPages', 'items']) {
    test('page metadata $field mismatch rejected', () {
      final page = {
        ...inventoryPage([partyJson(PartyKind.customer, 1)], total: 1),
        field: field == 'items' ? 'not an array' : -1,
      };
      expect(
        () => PartyListing.fromJson(
          {'page': page},
          a,
          page: 0,
          size: 20,
          mode: PartyMode.all,
        ),
        throwsA(isA<AppFailure>()),
      );
    });
  }
  test(
    'duplicate identity rejected and count/page need no transactional snapshot',
    () {
      final p = partyJson(PartyKind.customer, 1);
      expect(
        () => PartyListing.fromJson(
          {
            'page': inventoryPage([p, p], total: 2),
          },
          a,
          page: 0,
          size: 20,
          mode: PartyMode.all,
        ),
        throwsA(isA<AppFailure>()),
      );
      final result = PartyListing.fromJson(
        {
          'page': inventoryPage([p], total: 0),
        },
        a,
        page: 0,
        size: 20,
        mode: PartyMode.all,
      );
      expect(result.entries.items.length, 1);
    },
  );
  for (final pair in [
    ('2026-02-30', '2026-03-01'),
    ('2026-10-02', '2026-10-01'),
    ('2025-01-01', '2026-01-02'),
    ('1899-12-31', '1900-01-01'),
    ('9999-01-01', '9999-01-01'),
  ]) {
    test(
      'invalid account range ${pair.$1}..${pair.$2}',
      () => expect(
        () => PartyRange.custom(pair.$1, pair.$2),
        throwsA(isA<AppFailure>()),
      ),
    );
  }
  test('inclusive leap range and server preset names', () {
    expect(
      PartyRange.custom('2024-01-01', '2024-12-31').query['to'],
      '2024-12-31',
    );
    expect(PartyRange.preset(PartyPeriod.week).query, {'period': 'this_week'});
  });
  test(
    'invalid paging, IDs and oversized search are rejected before HTTP',
    () async {
      final f = await partyAuth();
      addTearDown(f.auth.dispose);
      final r = PartyRepository(f.auth.repository.client),
          before = f.transport.calls.length;
      for (final pair in [(-1, 20), (10001, 20), (0, 0), (0, 101)]) {
        await expectLater(
          r.list(a, page: pair.$1, size: pair.$2),
          throwsA(isA<AppFailure>()),
        );
      }
      for (final id in [0, -1, 2147483648]) {
        await expectLater(r.detail(a, id), throwsA(isA<AppFailure>()));
        await expectLater(r.account(a, id, month), throwsA(isA<AppFailure>()));
      }
      await expectLater(
        r.list(a, search: 'x' * 101),
        throwsA(isA<AppFailure>()),
      );
      expect(f.transport.calls.length, before);
    },
  );
  for (final status in [400, 403, 404, 500, 503]) {
    test('safe HTTP $status never exposes raw financial/SQL text', () async {
      final f = await partyAuth();
      addTearDown(f.auth.dispose);
      f.transport.handler = (_, _, _, _) async => throw AppFailure.http(
        status,
        {'message': 'SQL private balance 777.987'},
      );
      await expectLater(
        PartyRepository(f.auth.repository.client).detail(a, 1),
        throwsA(
          isA<AppFailure>().having(
            (e) => e.message,
            'safe',
            isNot(contains('777.987')),
          ),
        ),
      );
    });
  }
}
