import 'package:flutter_test/flutter_test.dart';
import 'package:manager_app/core/errors/app_failure.dart';
import 'package:manager_app/features/auth/data/auth_models.dart';
import 'package:manager_app/features/final_features/data/feature_access.dart';
import 'package:manager_app/features/quotations/data/quotation_models.dart';
import 'package:manager_app/features/quotations/data/quotation_repository.dart';
import 'package:manager_app/features/administration/data/admin_models.dart';
import 'package:manager_app/features/administration/data/admin_repository.dart';
import 'package:manager_app/features/audit/data/audit_repository.dart';
import 'package:manager_app/features/reports/data/report_repository.dart';
import 'package:manager_app/features/inventory/data/inventory_models.dart';

import 'support/final_feature_fixtures.dart';
import 'support/inventory_fixtures.dart';
import 'support/dashboard_fixtures.dart';

void main() {
  FeatureAccess access({
    List<String> permissions = finalPermissions,
    String role = 'ADMIN',
  }) => FeatureAccess(
    CurrentUser.fromJson(finalUser(permissions: permissions, role: role)),
  );
  final denied = throwsA(isA<AppFailure>());
  for (final role in ['ADMIN', 'ACCOUNTANT', 'CASHIER', 'STOREKEEPER']) {
    test('admin requires role and live permissions: $role', () {
      expect(access(role: role).users, role == 'ADMIN');
      expect(access(role: role).audit, role == 'ADMIN');
      expect(access(role: role, permissions: []).create, false);
      expect(access(role: role, permissions: []).edit, false);
      expect(access(role: role, permissions: []).reset, false);
    });
  }
  for (final status in QuotationStatus.values) {
    test(
      'quotation preserves stored ${status.api} independently of validity and draft link',
      () {
        final q = Quotation.fromJson(
          quotationJson(status: status.api),
          access(),
        );
        expect(q.status, status.api);
        expect(q.pastValidity, true);
        expect(q.linkedStatus, 'DRAFT');
        expect(q.total, '9007199254740993.125');
        expect(q.toString(), isNot(contains(q.total)));
      },
    );
  }
  test('prospect customer is nullable without fabricated identity', () {
    final q = Quotation.fromJson(quotationJson(prospect: true), access());
    expect(q.customerId, null);
    expect(q.customerName, null);
    expect(q.prospectName, 'عميل محتمل');
  });
  test('quotation link redaction discards even malformed over-return', () {
    final raw = quotationJson()..['linkedSale'] = 'sensitive invalid blob';
    final q = Quotation.fromJson(raw, access(permissions: ['QUOTATIONS_VIEW']));
    expect(q.linkedId, null);
    expect(q.linkedNumber, null);
    expect(() => Quotation.fromJson(raw, access()), denied);
  });
  test('quotation nullable validity/timestamps do not invent expiry', () {
    final raw = quotationJson()
      ..['validUntil'] = null
      ..['pastValidity'] = false
      ..['sentAt'] = null;
    final q = Quotation.fromJson(raw, access());
    expect(q.validUntil, null);
    expect(q.pastValidity, false);
    expect(q.sentAt, null);
  });
  for (final field in ['id', 'total', 'quantity', 'date', 'pastValidity']) {
    test('quotation malformed $field fails safely', () {
      if (field == 'quantity') {
        expect(
          () => QuotationLine.fromJson(quotationLine()..[field] = 2.5),
          denied,
        );
      } else {
        expect(
          () => Quotation.fromJson(
            quotationJson()..[field] = 'invalid',
            access(),
          ),
          denied,
        );
      }
    });
  }
  for (final sort in QuotationSort.values) {
    test(
      'quotation frozen sort ${sort.api} and literal encoded search',
      () async {
        final f = await finalAuth();
        addTearDown(f.auth.dispose);
        final query = QuotationQuery(
          search: '  100%_[ O\'Reilly;--  ',
          sort: sort,
          status: QuotationStatus.sent,
          customerId: 2,
          pastValidity: false,
        );
        await QuotationRepository(f.auth.repository.client)
            .list(access(), query, 0, 20);
        final request = f.transport.calls.single,
            q = Uri.parse(request.path).queryParameters;
        expect(request.method, 'GET');
        expect(q['sort'], sort.api);
        expect(q['q'], "100%_[ O'Reilly;--");
        expect(q['status'], 'SENT');
        expect(q['customerId'], '2');
        expect(q['pastValidity'], 'false');
      },
    );
  }
  test('quotation detail nested page/header and ID validation', () async {
    final f = await finalAuth();
    addTearDown(f.auth.dispose);
    final r = QuotationRepository(f.auth.repository.client);
    final result = await r.detail(access(), 1, 0, 20);
    expect(result.entries.items.single.total, '9.187');
    expect(result.header, isA<Quotation>());
    await expectLater(r.detail(access(), 0, 0, 20), denied);
    await expectLater(r.detail(access(), 2, 0, 20), denied);
  });
  for (final pair in [(10001, 20), (-1, 20), (0, 0), (0, 101)]) {
    test('quotation page bounds $pair', () async {
      final f = await finalAuth();
      addTearDown(f.auth.dispose);
      await expectLater(
        QuotationRepository(f.auth.repository.client)
            .list(access(), QuotationQuery(), pair.$1, pair.$2),
        denied,
      );
      expect(f.transport.calls, isEmpty);
    });
  }
  test('quotation page mismatch/duplicate identities rejected', () async {
    final f = await finalAuth();
    addTearDown(f.auth.dispose);
    f.transport.handler = (m, p, b, t) async =>
        inventoryPage([quotationJson(), quotationJson()]);
    await expectLater(
      QuotationRepository(f.auth.repository.client)
          .list(access(), QuotationQuery(), 0, 20),
      denied,
    );
  });
  test('read permission rejection does not send a request', () async {
    final f = await finalAuth();
    addTearDown(f.auth.dispose);
    await expectLater(
      QuotationRepository(f.auth.repository.client)
          .list(access(permissions: []), QuotationQuery(), 0, 20),
      denied,
    );
    await expectLater(
      AuditRepository(f.auth.repository.client)
          .list(access(role: 'CASHIER'), AuditQuery(), 0, 20),
      denied,
    );
    await expectLater(
      AdminRepository(f.auth.repository.client)
          .metadata(access(permissions: [])),
      denied,
    );
    expect(f.transport.calls, isEmpty);
  });
  for (final key in ['action', 'category', 'userId']) {
    test('audit allowlist/bounds rejects $key', () async {
      final f = await finalAuth();
      addTearDown(f.auth.dispose);
      final q = AuditQuery();
      if (key == 'action') q.action = 'OTHER';
      if (key == 'category') q.category = 'secret';
      if (key == 'userId') q.userId = 0;
      await expectLater(
        AuditRepository(f.auth.repository.client).list(access(), q, 0, 20),
        denied,
      );
      expect(f.transport.calls, isEmpty);
    });
  }
  test('audit allowed filters and unknown payload privacy', () async {
    final f = await finalAuth();
    addTearDown(f.auth.dispose);
    final q = AuditQuery()
      ..userId = 1
      ..action = 'PASSWORD_RESET'
      ..category = 'Users';
    await AuditRepository(f.auth.repository.client).list(access(), q, 0, 20);
    final params = Uri.parse(f.transport.calls.single.path).queryParameters;
    expect(params['sort'], 'date,desc');
    expect(params['action'], 'PASSWORD_RESET');
    expect(params.containsKey('q'), false);
    final event = AuditEvent.fromJson(
      auditEvent()
        ..['action'] = 'legacy-secret'
        ..['category'] = 'legacy'
        ..['description'] = 'private payload',
    );
    expect(event.action, 'OTHER');
    expect(event.category, 'OTHER');
    expect(event.toString(), isNot(contains('private')));
  });
  test('audit absent actor stays absent', () {
    final event = AuditEvent.fromJson(
      auditEvent()
        ..['userId'] = null
        ..['username'] = null
        ..['fullName'] = null,
    );
    expect(event.userId, null);
    expect(event.username, null);
  });
  test('expenses retain categories and authoritative exact values', () async {
    final f = await finalAuth();
    addTearDown(f.auth.dispose);
    final r = await ReportRepository(f.auth.repository.client)
        .expenses(access(), const MovementRange.preset(MovementPeriod.today));
    expect(r.summary.total, '10.125');
    expect(r.categories.single.amount, '10.125');
    expect(r.categories.single.entries, 1);
  });
  test('cash range and exact backend movement/closing retained', () async {
    final f = await finalAuth();
    addTearDown(f.auth.dispose);
    final r = await ReportRepository(f.auth.repository.client)
        .cash(access(), MovementRange.custom(businessDate, businessDate));
    expect(r.opening, '100.000');
    expect(r.closing, '115.000');
  });
  test(
    'report wrong explicit range fails, no silently substituted period',
    () async {
      final f = await finalAuth();
      addTearDown(f.auth.dispose);
      await expectLater(
        ReportRepository(f.auth.repository.client)
            .cash(access(), MovementRange.custom('2026-10-01', '2026-10-02')),
        denied,
      );
    },
  );
  test('daily ignores unauthorized over-returned financial sections', () async {
    final f = await finalAuth();
    addTearDown(f.auth.dispose);
    final d = await ReportRepository(f.auth.repository.client)
        .daily(access(permissions: ['DASHBOARD']), businessDate);
    expect(d.sales, null);
    expect(d.expenses, null);
    expect(d.cash, null);
    expect(d.inventory, null);
  });
  test('daily current inventory remains distinct from requested day', () async {
    final f = await finalAuth();
    addTearDown(f.auth.dispose);
    f.transport.handler = (m, p, b, t) async => fixtureDaily()
      ..['inventorySnapshot'] = {
        'businessDate': '2026-10-09',
        'inventory': fixtureInventory(),
      };
    final d = await ReportRepository(f.auth.repository.client)
        .daily(access(), businessDate);
    expect(d.date.iso, businessDate);
    expect(d.inventoryDate!.iso, '2026-10-09');
  });
  test('slow search/paging bounds and cost redaction', () async {
    final f = await finalAuth();
    addTearDown(f.auth.dispose);
    final repo = ReportRepository(f.auth.repository.client);
    final result = await repo.slow(
      access(permissions: ['INVENTORY', 'REPORTS_VIEW', 'REPORTS_INVENTORY']),
      3650,
      '100%_',
      0,
      20,
    );
    expect(result.entries.items.single.valueAtCost, null);
    expect(
      Uri.parse(f.transport.calls.single.path).queryParameters['days'],
      '3650',
    );
    await expectLater(repo.slow(access(), 0, '', 0, 20), denied);
  });
  for (final sort in AdminSort.values) {
    test('users allowlisted sort ${sort.api}', () async {
      final f = await finalAuth();
      addTearDown(f.auth.dispose);
      final repo = AdminRepository(f.auth.repository.client),
          metadata = await repo.metadata(access());
      final q = AdminQuery()
        ..sort = sort
        ..search = '100%_'
        ..role = 'CASHIER'
        ..active = false;
      final result = await repo.list(access(), q, 0, 20, metadata: metadata);
      expect(result.entries.items.single.username, 'user2');
      final params = Uri.parse(f.transport.calls.last.path).queryParameters;
      expect(params['sort'], sort.api);
      expect(params['role'], 'CASHIER');
      expect(params['active'], 'false');
    });
  }
  test('users detail strips hash/password/version over-return', () async {
    final f = await finalAuth();
    addTearDown(f.auth.dispose);
    f.transport.handler = (m, p, b, t) async => adminUser()
      ..['password'] = 'private'
      ..['passwordHash'] = 'hash';
    final u = await AdminRepository(f.auth.repository.client)
        .detail(access(), 2);
    expect(u.toString(), isNot(contains('private')));
    await expectLater(
      AdminRepository(f.auth.repository.client).detail(access(), 3),
      denied,
    );
  });
  test('create normalizes username, omits active and preserves explicit temporary password body', () async {
    final f = await finalAuth();
    addTearDown(f.auth.dispose);
    final repo = AdminRepository(f.auth.repository.client),
        metadata = await repo.metadata(access());
    f.transport.handler = (m, p, b, t) async => {
      ...adminUser(3),
      'username': 'new.user',
    };
    final u = await repo.create(
      access(),
      metadata,
      ' New.User ',
      AdminProfile(fullName: 'اسم جديد', roleCode: 'CASHIER', active: false),
      'FixturePass12',
      'FixturePass12',
    );
    final request = f.transport.calls.last, body = request.body as Map;
    expect(request.method, 'POST');
    expect(body['username'], 'new.user');
    expect(body.containsKey('active'), false);
    expect(u.mustChange, true);
  });
  test('edit is full replacement and excludes immutable username', () async {
    final f = await finalAuth();
    addTearDown(f.auth.dispose);
    final repo = AdminRepository(f.auth.repository.client),
        metadata = await repo.metadata(access());
    await repo.edit(
      access(),
      metadata,
      AdminUser.fromJson(adminUser()),
      AdminProfile(fullName: 'الاسم', roleCode: 'CASHIER', active: false),
    );
    final request = f.transport.calls.last, body = request.body as Map;
    expect(request.method, 'PUT');
    expect(body.keys.toSet(), {
      'fullName',
      'phone',
      'email',
      'roleCode',
      'active',
    });
    expect(body['active'], false);
  });
  for (final enabled in [true, false]) {
    test(
      'released ${enabled ? 'enable' : 'disable'} only POSTs selected user',
      () async {
        final f = await finalAuth();
        addTearDown(f.auth.dispose);
        await AdminRepository(f.auth.repository.client)
            .active(access(), AdminUser.fromJson(adminUser()), enabled);
        expect(f.transport.calls.single.method, 'POST');
        expect(
          f.transport.calls.single.path,
          'manager/admin/users/2/${enabled ? 'enable' : 'disable'}',
        );
      },
    );
  }
  test('reset body contains only supplied password/confirmation and never returns them', () async {
    final f = await finalAuth();
    addTearDown(f.auth.dispose);
    final u = await AdminRepository(f.auth.repository.client).reset(
      access(),
      AdminUser.fromJson(adminUser()),
      'FixturePass12',
      'FixturePass12',
    );
    expect(
      f.transport.calls.single.path,
      'manager/admin/users/2/reset-password',
    );
    expect(u.mustChange, true);
    expect(u.toString(), isNot(contains('FixturePass12')));
  });
  for (final op in ['create', 'edit', 'active', 'reset']) {
    test('$op requires its own live permission', () async {
      final f = await finalAuth();
      addTearDown(f.auth.dispose);
      final repo = AdminRepository(f.auth.repository.client),
          meta = await repo.metadata(access());
      f.transport.calls.clear();
      final target = AdminUser.fromJson(adminUser()),
          profile = AdminProfile(
            fullName: 'اسم',
            roleCode: 'CASHIER',
            active: true,
          ),
          a = access(permissions: ['USERS_VIEW']);
      final operation = switch (op) {
        'create' => repo.create(
          a,
          meta,
          'newuser',
          profile,
          'FixturePass12',
          'FixturePass12',
        ),
        'edit' => repo.edit(a, meta, target, profile),
        'active' => repo.active(a, target, false),
        _ => repo.reset(a, target, 'FixturePass12', 'FixturePass12'),
      };
      await expectLater(operation, denied);
      expect(f.transport.calls, isEmpty);
    });
  }
  test('self disable/reset/role change prevented, last admin remains server-authoritative', () async {
    final f = await finalAuth();
    addTearDown(f.auth.dispose);
    final repo = AdminRepository(f.auth.repository.client),
        meta = await repo.metadata(access()),
        self = AdminUser.fromJson({...adminUser(1), 'roleCode': 'ADMIN'});
    await expectLater(repo.active(access(), self, false), denied);
    await expectLater(
      repo.reset(access(), self, 'FixturePass12', 'FixturePass12'),
      denied,
    );
    await expectLater(
      repo.edit(
        access(),
        meta,
        self,
        AdminProfile(fullName: 'اسم', roleCode: 'CASHIER', active: true),
      ),
      denied,
    );
    f.transport.handler = (m, p, b, t) async => throw AppFailure.http(400, {
      'code': 'VALIDATION_ERROR',
      'message': 'raw SQL password',
    });
    await expectLater(
      repo.active(access(), AdminUser.fromJson(adminUser(4)), false),
      throwsA(
        isA<AppFailure>().having(
          (f) => f.message,
          'safe message',
          isNot(contains('raw SQL')),
        ),
      ),
    );
  });
  for (final value in [
    'short1',
    'NoDigitsHere',
    '123456789',
    'user2',
    'FixturePass12',
  ]) {
    test('password validation $value/confirmation', () {
      expect(() => adminPassword(value, 'different', 'user2'), denied);
    });
  }
  test('unicode letters/digits and exact password confirmation follow released policy', () {
    adminPassword('كلمةمرور١٢٣', 'كلمةمرور١٢٣', 'fixture');
    expect(
      () => adminPassword('FixturePass12', 'fixturePass12', 'fixture'),
      denied,
    );
  });
}
