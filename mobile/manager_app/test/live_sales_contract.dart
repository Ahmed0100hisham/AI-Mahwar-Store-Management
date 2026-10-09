// Explicit opt-in: real HTTP/SQL verification against disposable databases only.
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:manager_app/core/config/app_config.dart';
import 'package:manager_app/core/errors/app_failure.dart';
import 'package:manager_app/core/network/api_transport.dart';
import 'package:manager_app/features/auth/data/auth_repository.dart';
import 'package:manager_app/features/auth/state/auth_controller.dart';
import 'package:manager_app/features/sales/data/sales_access.dart';
import 'package:manager_app/features/sales/data/sales_models.dart';
import 'package:manager_app/features/sales/data/sales_repository.dart';
import 'package:manager_app/features/sales/state/sales_controller.dart';

import 'support/fakes.dart';

class SalesObservedTransport implements ApiTransport {
  SalesObservedTransport(this.inner);
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
    final value = await inner.send(
      method,
      path,
      body: body,
      accessToken: accessToken,
    );
    if (path.startsWith('manager/')) {
      responses.add((method: method, path: path, body: value));
    }
    return value;
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
  ({AuthController auth, SalesObservedTransport transport}) create() {
    final dio = Dio(),
        transport = SalesObservedTransport(
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

  for (final role in ['ADMIN', 'ACCOUNTANT', 'CASHIER', 'STOREKEEPER']) {
    test(
      'live $role frozen endpoints permissions redaction filters pagination no-store',
      () async {
        final f = create();
        try {
          expect(
            await f.auth.login('p3_$role', password),
            isTrue,
            reason: f.auth.error?.code,
          );
          final access = SalesAccess(f.auth.user),
              repo = SalesRepository(f.auth.repository.client),
              range = SalesRange.custom('2026-10-01', '2026-10-07');
          if (access.invoices) {
            final listed = await repo.invoices(
              access,
              range,
              search: 'P3-',
              size: 1,
              sort: InvoiceSort.oldest,
            );
            expect(listed.total, 4);
            expect(listed.items.single.number, 'P3-1');
            final page2 = await repo.invoices(
              access,
              range,
              search: 'P3-',
              size: 1,
              page: 1,
              sort: InvoiceSort.oldest,
            );
            expect(page2.items.single.id, isNot(listed.items.single.id));
            final detail = await repo.detail(
              access,
              listed.items.single.id,
              size: 1,
              returnsSize: 1,
            );
            expect(detail.invoice.total, '10.000');
            expect(detail.invoice.returnedAmount, '2.500');
            expect(detail.invoice.netAmount, '7.500');
            expect(detail.items.items.single.returnedQuantity, '1.000');
            expect(
              detail.invoice.historicalCost,
              access.invoiceCost ? '5.000' : null,
            );
            expect(
              detail.invoice.grossProfit,
              access.invoiceProfit ? '5.000' : null,
            );
            expect(
              detail.items.items.single.historicalUnitCost,
              access.invoiceCost ? '1.250' : null,
            );
            final raw = f.transport.responses.last.body as Map,
                header = raw['invoice'] as Map,
                line = ((raw['items'] as Map)['items'] as List).single as Map;
            expect(header.containsKey('historicalCost'), access.invoiceCost);
            expect(header.containsKey('grossProfit'), access.invoiceProfit);
            expect(line.containsKey('historicalUnitCost'), access.invoiceCost);
            for (final sort in InvoiceSort.values) {
              await repo.invoices(access, range, sort: sort, search: 'P3-');
            }
            final posted = await repo.invoices(
              access,
              range,
              status: InvoiceStatus.posted,
              customerId: detail.invoice.customer.id,
            );
            expect(posted.total, 2);
            expect(
              (await repo.invoices(
                access,
                range,
                search: "100%_[ O'Reilly",
              )).total,
              4,
            );
            expect(
              (await repo.invoices(access, range, search: "' OR 1=1 --")).items,
              isEmpty,
            );
            final draft = await repo.invoices(
              access,
              range,
              status: InvoiceStatus.draft,
            );
            expect(draft.items.single.grossProfit, isNull);
            final cancelled = await repo.invoices(
              access,
              range,
              status: InvoiceStatus.cancelled,
            );
            expect(cancelled.items.single.grossProfit, isNull);
          } else {
            await expectLater(
              repo.invoices(access, range),
              throwsA(isA<AppFailure>()),
            );
            await expectLater(
              f.auth.repository.client.send(
                'GET',
                'manager/invoices?from=2026-10-01&to=2026-10-07',
              ),
              throwsA(isA<AppFailure>().having((e) => e.status, 'status', 403)),
            );
          }
          if (access.reports) {
            final report = await repo.report(access, range);
            expect(report.summary.grossSales, '20.000');
            expect(report.summary.returns, '5.625');
            expect(report.summary.netSales, '14.375');
            expect(report.summary.averageInvoice, '10.000');
            expect(
              report.summary.netProfit,
              // 20 - 8 historical cost - (5.625 - 2.500 returned cost)
              // - 4.125 expenses = 4.750. No client accounting calculation.
              access.reportProfit ? '4.750' : null,
            );
            expect(report.trend.length, 7);
            final raw =
                f.transport.responses
                        .firstWhere(
                          (r) => r.path.startsWith('manager/sales/summary'),
                        )
                        .body
                    as Map;
            expect(raw.containsKey('profit'), access.reportProfit);
          } else {
            await expectLater(
              f.auth.repository.client.send(
                'GET',
                'manager/sales/summary?from=2026-10-01&to=2026-10-07',
              ),
              throwsA(isA<AppFailure>().having((e) => e.status, 'status', 403)),
            );
          }
          expect(f.transport.responses.every((c) => c.method == 'GET'), isTrue);
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
  test('live invoice independent nested pages, later returns, historical stored tax/payment', () async {
    final f = create();
    try {
      expect(await f.auth.login('p3_ADMIN', password), isTrue);
      final access = SalesAccess(f.auth.user),
          repo = SalesRepository(f.auth.repository.client);
      final list = await repo.invoices(
        access,
        SalesRange.custom('2026-11-01', '2026-11-01'),
        search: 'P4-فاتورة',
      );
      expect(list.total, 1);
      final original = list.items.single;
      expect(original.returnCutoff!.iso, '2026-11-01');
      expect(original.returnedAmount, '0.000');
      expect(original.netAmount, '12.125');
      final detail = await repo.detail(
        access,
        original.id,
        size: 1,
        returnsSize: 1,
      );
      expect(detail.items.total, 2);
      expect(detail.returns.total, 2);
      expect(detail.invoice.subtotal, '14.000');
      expect(detail.invoice.discount, '2.000');
      expect(detail.invoice.tax, '0.125');
      expect(detail.invoice.total, '12.125');
      expect(detail.invoice.paid, '5.000');
      expect(detail.invoice.remaining, '7.125');
      expect(detail.invoice.paymentStatus, 'PARTIAL');
      expect(detail.invoice.paymentMethod, 'MIXED');
      expect(detail.invoice.returnedAmount, '4.676');
      expect(detail.invoice.refundedAmount, '1.117');
      expect(detail.invoice.netAmount, '7.449');
      expect(detail.invoice.historicalCost, '5.000');
      expect(detail.invoice.grossProfit, '7.125');
      expect(detail.items.items.single.quantity, '2.500');
      expect(detail.items.items.single.discount, '1.000');
      expect(detail.items.items.single.total, '9.000');
      expect(detail.items.items.single.historicalUnitCost, '1.200');
      expect(detail.items.items.single.returnedQuantity, '1.500');
      final secondLine = await repo.detail(
        access,
        original.id,
        page: 1,
        size: 1,
        returnsSize: 1,
      );
      expect(
        secondLine.items.items.single.id,
        isNot(detail.items.items.single.id),
      );
      expect(
        secondLine.returns.items.single.id,
        detail.returns.items.single.id,
      );
      final secondReturn = await repo.detail(
        access,
        original.id,
        size: 1,
        returnsPage: 1,
        returnsSize: 1,
      );
      expect(secondReturn.items.items.single.id, detail.items.items.single.id);
      expect(secondReturn.returns.items.single.date, '2026-11-03T23:59:59');
      expect(secondReturn.returns.items.single.refund, '1.117');
      final partial = await repo.invoices(
        access,
        SalesRange.custom('2026-11-01', '2026-11-02'),
        search: 'P4-فاتورة',
      );
      expect(partial.items.single.returnedAmount, '1.559');
      expect(partial.items.single.refundedAmount, '0.000');
      expect(partial.items.single.netAmount, '10.566');
    } finally {
      await f.auth.logout();
      f.auth.dispose();
    }
  });
  test(
    'live return-only day on older invoice remains negative on return date',
    () async {
      final f = create();
      try {
        expect(await f.auth.login('p3_ADMIN', password), isTrue);
        final report = await SalesRepository(f.auth.repository.client).report(
          SalesAccess(f.auth.user),
          SalesRange.custom('2026-10-03', '2026-10-03'),
        );
        expect(report.summary.grossSales, '0.000');
        expect(report.summary.returns, '2.500');
        expect(report.summary.netSales, '-2.500');
        expect(report.summary.invoiceCount, 0);
        expect(report.trend.single.netSales, '-2.500');
      } finally {
        await f.auth.logout();
        f.auth.dispose();
      }
    },
  );
  test(
    'live restricted session blocked before financial requests and by backend',
    () async {
      final f = create(),
          state = InvoiceListController(
            f.auth,
            SalesRepository(f.auth.repository.client),
          );
      try {
        expect(await f.auth.login('flutter_restricted', password), isTrue);
        expect(f.auth.status, AuthStatus.restricted);
        await state.load();
        expect(state.items, isEmpty);
        expect(f.transport.responses, isEmpty);
        await expectLater(
          f.auth.repository.client.send('GET', 'manager/invoices'),
          throwsA(isA<AppFailure>()),
        );
      } finally {
        state.dispose();
        await f.auth.logout();
        f.auth.dispose();
      }
    },
  );
  test('live revoked session clears report/list/detail through centralized authentication', () async {
    final f = create(),
        second = create(),
        repo = SalesRepository(f.auth.repository.client),
        list = InvoiceListController(
          f.auth,
          SalesRepository(f.auth.repository.client),
        ),
        overview = SalesOverviewController(
          f.auth,
          SalesRepository(f.auth.repository.client),
        );
    InvoiceDetailController? detail;
    try {
      expect(await f.auth.login('p3_ADMIN', password), isTrue);
      expect(await second.auth.login('p3_ADMIN', password), isTrue);
      list.setRange(SalesRange.custom('2026-10-01', '2026-10-07'));
      await list.load();
      overview.setRange(SalesRange.custom('2026-10-01', '2026-10-07'));
      await overview.load();
      detail = InvoiceDetailController(f.auth, repo, list.items.first.id);
      await detail.load();
      expect(detail.invoice, isNotNull);
      expect(overview.data, isNotNull);
      await second.auth.repository.revoke(f.auth.sid!);
      await detail.load();
      expect(f.auth.status, AuthStatus.signedOut);
      expect(list.items, isEmpty);
      expect(detail.invoice, isNull);
      expect(overview.data, isNull);
    } finally {
      detail?.dispose();
      list.dispose();
      overview.dispose();
      await f.auth.logout();
      await second.auth.logout();
      f.auth.dispose();
      second.auth.dispose();
    }
  });
}
