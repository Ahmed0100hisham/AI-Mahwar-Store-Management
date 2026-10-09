import 'package:flutter_test/flutter_test.dart';
import 'package:manager_app/core/errors/app_failure.dart';
import 'package:manager_app/core/utils/money.dart';
import 'package:manager_app/features/auth/data/auth_models.dart';
import 'package:manager_app/features/sales/data/sales_access.dart';
import 'package:manager_app/features/sales/data/sales_models.dart';
import 'package:manager_app/features/sales/data/sales_repository.dart';

import 'support/fakes.dart';
import 'support/inventory_fixtures.dart';
import 'support/sales_fixtures.dart';

void main() {
  SalesAccess access([List<String> codes = salesPermissions]) =>
      SalesAccess(CurrentUser.fromJson(userJson(permissions: codes)));
  const month = SalesRange.preset(SalesPeriod.month);
  test('independent report, invoice, cost and profit codes; role name grants nothing', () {
    expect(access([]).enter, isFalse);
    expect(access(['REPORTS_VIEW']).reports, isFalse);
    expect(access(['REPORTS_SALES']).reports, isFalse);
    final reports = access([
      'REPORTS_VIEW',
      'REPORTS_SALES',
      'REPORTS_PROFIT',
      'PRODUCT_COST',
    ]);
    expect(reports.reports, isTrue);
    expect(reports.invoices, isFalse);
    expect(reports.invoiceCost, isFalse);
    expect(reports.invoiceProfit, isFalse);
    final invoices = access([
      'SALES_VIEW',
      'SALES_COST_VIEW',
      'SALES_PROFIT_VIEW',
    ]);
    expect(invoices.invoices, isTrue);
    expect(invoices.reports, isFalse);
    expect(invoices.reportProfit, isFalse);
  });
  for (final value in ['0.000', '-2.125', '1.125', '9007199254740993.125']) {
    test('invoice, line and refund preserve exact decimal $value', () {
      final invoice = SalesInvoice.fromJson({
        ...invoiceJson(1),
        'total': value,
        'netAmount': value,
      }, access());
      final line = InvoiceLine.fromJson({
        ...invoiceLineJson(),
        'quantity': value,
        'total': value,
      }, access());
      final returned = InvoiceReturn.fromJson({
        ...invoiceReturnJson(),
        'refund': value,
      });
      expect(invoice.total, value);
      expect(invoice.netAmount, value);
      expect(line.quantity, value);
      expect(line.total, value);
      expect(returned.refund, value);
      expect(formatKwd(value), '${formatQuantity(value)} د.ك');
      expect(formatQuantity(value).replaceAll(',', ''), value);
    });
  }
  test('list cutoff differs from all-time detail, original payment is not rewritten', () {
    final list = SalesInvoice.fromJson(invoiceJson(1), access());
    final detail = SalesInvoice.fromJson(
      invoiceJson(1, detail: true),
      access(),
      detail: true,
    );
    expect(list.returnCutoff!.iso, '2026-10-31');
    expect(list.returnedAmount, '3.125');
    expect(detail.returnCutoff, isNull);
    expect(detail.returnedAmount, '9.000');
    expect(detail.netAmount, '2.375');
    expect(detail.refundedAmount, '2.000');
    expect(detail.remaining, '6.375');
    expect(detail.paid, '5.000');
    expect(detail.tax, '2.500');
    expect(InvoiceLine.fromJson(invoiceLineJson(), access()).discount, '1.125');
    expect(InvoiceLine.fromJson(invoiceLineJson(), access()).total, '9.500');
  });
  test('unauthorized over-returned malformed cost/profit discarded, diagnostics private', () {
    final invoice = SalesInvoice.fromJson({
      ...invoiceJson(1),
      'historicalCost': {'secret': 'x'},
      'grossProfit': 42,
    }, access(['SALES_VIEW', 'REPORTS_PROFIT', 'PRODUCT_COST']));
    final line = InvoiceLine.fromJson({
      ...invoiceLineJson(),
      'historicalUnitCost': 'invalid',
    }, access(['SALES_VIEW']));
    expect(invoice.historicalCost, isNull);
    expect(invoice.grossProfit, isNull);
    expect(line.historicalUnitCost, isNull);
    for (final object in [
      invoice,
      line,
      invoice.customer,
      InvoiceReturn.fromJson(invoiceReturnJson()),
    ]) {
      expect(object.toString(), contains('[PRIVATE]'));
      expect(object.toString(), isNot(contains('7.987')));
    }
  });
  for (final status in ['POSTED', 'DRAFT', 'CANCELLED']) {
    test('original profit only for posted invoice: $status', () {
      final i = SalesInvoice.fromJson({
        ...invoiceJson(1),
        'status': status,
      }, access());
      expect(i.historicalCost, '7.987');
      expect(i.grossProfit, status == 'POSTED' ? '3.388' : null);
    });
  }
  test(
    'missing authorized optional money stays absent; malformed present fails',
    () {
      final raw = invoiceJson(1)
        ..remove('historicalCost')
        ..remove('grossProfit');
      final invoice = SalesInvoice.fromJson(raw, access());
      expect(invoice.historicalCost, isNull);
      expect(invoice.grossProfit, isNull);
      for (final key in ['historicalCost', 'grossProfit']) {
        expect(
          () => SalesInvoice.fromJson({...raw, key: '7.98'}, access()),
          throwsA(isA<AppFailure>()),
        );
      }
      final line = invoiceLineJson()..remove('historicalUnitCost');
      expect(InvoiceLine.fromJson(line, access()).historicalUnitCost, isNull);
      expect(
        () => InvoiceLine.fromJson({
          ...line,
          'historicalUnitCost': 1.0,
        }, access()),
        throwsA(isA<AppFailure>()),
      );
    },
  );
  for (final key in [
    'id',
    'number',
    'date',
    'customer',
    'creator',
    'status',
    'priceType',
    'paymentMethod',
    'paymentStatus',
    'subtotal',
    'discount',
    'tax',
    'total',
    'paid',
    'remaining',
    'returnedAmount',
    'refundedAmount',
    'netAmount',
    'returnCutoffDate',
  ]) {
    test('malformed invoice cannot become plausible zero: missing $key', () {
      final raw = invoiceJson(1)..remove(key);
      expect(
        () => SalesInvoice.fromJson(raw, access()),
        throwsA(isA<AppFailure>()),
      );
    });
  }
  test(
    'timestamps validate real local business dates and reject zones/rollovers',
    () {
      for (final value in [
        '2026-02-30T12:00:00',
        '2026-10-01T24:00:00',
        '2026-10-01T12:60:00',
        '2026-10-01T12:00:60',
        '2026-10-01T12:00:00Z',
        '2026-10-01T12:00:00+03:00',
        '1899-01-01T00:00:00',
      ]) {
        expect(
          () => SalesInvoice.fromJson({
            ...invoiceJson(1),
            'date': value,
          }, access()),
          throwsA(isA<AppFailure>()),
          reason: value,
        );
      }
      expect(
        SalesInvoice.fromJson({
          ...invoiceJson(1),
          'date': '2024-02-29T23:59:59.125',
        }, access()).date,
        '2024-02-29T23:59:59.125',
      );
    },
  );
  test('unknown status/method stays literal; nullable references do not invent fields', () {
    expect(invoiceStateLabel('FUTURE_STATE'), 'FUTURE_STATE');
    expect(paymentMethodLabel('FUTURE_METHOD'), 'FUTURE_METHOD');
    expect(paymentStateLabel('FUTURE_PAYMENT'), 'FUTURE_PAYMENT');
    expect(priceTypeLabel('SPECIAL'), 'SPECIAL');
    expect(
      SalesInvoice.fromJson(invoiceJson(1), access()).customer.phone,
      isNull,
    );
    expect(
      InvoiceReturn.fromJson({...invoiceReturnJson(), 'refundMethod': null})
          .refundMethod,
      isNull,
    );
    expect(
      () => InvoiceReturn.fromJson({
        ...invoiceReturnJson(),
        'refundMethod': false,
      }),
      throwsA(isA<AppFailure>()),
    );
  });
  test('report anchored to returned business range; no dashboard permission or clock', () async {
    final f = await salesAuth(permissions: ['REPORTS_VIEW', 'REPORTS_SALES']);
    addTearDown(f.auth.dispose);
    f.transport.calls.clear();
    final report = await SalesRepository(f.auth.repository.client)
        .report(access(['REPORTS_VIEW', 'REPORTS_SALES']), month);
    expect(report.summary.range.from.iso, '2026-10-01');
    expect(report.summary.range.to.iso, '2026-10-31');
    expect(report.trend.length, 31);
    expect(report.summary.netSales, '9007199254740993.125');
    expect(report.summary.netProfit, isNull);
    expect(report.top.single.profit, isNull);
    expect(
      f.transport.calls.first.path,
      'manager/sales/summary?period=this_month',
    );
    for (final c in f.transport.calls.skip(1)) {
      expect(c.method, 'GET');
      expect(Uri.parse(c.path).queryParameters['from'], '2026-10-01');
      expect(Uri.parse(c.path).queryParameters['to'], '2026-10-31');
      expect(Uri.parse(c.path).queryParameters.containsKey('period'), isFalse);
    }
  });
  test(
    'week Sunday and today originate from server; custom inclusive max366',
    () async {
      final f = await salesAuth();
      addTearDown(f.auth.dispose);
      final repo = SalesRepository(f.auth.repository.client);
      final week = await repo.report(
        access(),
        const SalesRange.preset(SalesPeriod.week),
      );
      expect(week.summary.range.from.iso, '2026-10-04');
      expect(week.summary.range.to.iso, '2026-10-08');
      final today = await repo.report(
        access(),
        const SalesRange.preset(SalesPeriod.today),
      );
      expect(today.summary.range.from.iso, '2026-10-08');
      final leap = SalesRange.custom('2024-01-01', '2024-12-31');
      expect((await repo.report(access(), leap)).trend.length, 366);
      for (final dates in [
        ('2024-01-01', '2025-01-01'),
        ('2026-10-02', '2026-10-01'),
        ('2026-02-30', '2026-03-01'),
      ]) {
        expect(
          () => SalesRange.custom(dates.$1, dates.$2),
          throwsA(isA<AppFailure>()),
        );
      }
    },
  );
  for (final corruption in [
    'range',
    'grouping',
    'missing-day',
    'duplicate-day',
    'top-limit',
  ]) {
    test('malformed cross-endpoint report rejected: $corruption', () async {
      final f = await salesAuth();
      addTearDown(f.auth.dispose);
      f.transport.handler = (_, p, _, _) async {
        final raw = salesResponse(p) as Map<String, Object?>;
        if (p.startsWith('manager/sales/trend')) {
          if (corruption == 'range') {
            raw['range'] = {'from': '2026-10-02', 'to': '2026-10-31'};
          }
          if (corruption == 'grouping') raw['grouping'] = 'weekly';
          if (corruption == 'missing-day') {
            (raw['buckets'] as List).removeLast();
          }
          if (corruption == 'duplicate-day') {
            (raw['buckets'] as List)[1] = (raw['buckets'] as List)[0];
          }
        }
        if (corruption == 'top-limit' &&
            p.startsWith('manager/sales/top-products')) {
          raw['limit'] = 100;
        }
        return raw;
      };
      await expectLater(
        SalesRepository(f.auth.repository.client).report(access(), month),
        throwsA(isA<AppFailure>()),
      );
    });
  }
  test('invoice server search literal, all filters allowlisted, no paymentStatus query', () async {
    final f = await salesAuth();
    addTearDown(f.auth.dispose);
    final repo = SalesRepository(f.auth.repository.client);
    await repo.invoices(
      access(),
      month,
      search: "  %_' OR 1=1 --  ",
      status: InvoiceStatus.posted,
      payment: InvoicePayment.mixed,
      customerId: 1,
      sort: InvoiceSort.highest,
      size: 2,
    );
    expect(Uri.parse(f.transport.calls.last.path).queryParameters, {
      'period': 'this_month',
      'page': '0',
      'size': '2',
      'sort': 'total,desc',
      'q': "%_' OR 1=1 --",
      'status': 'POSTED',
      'paymentMethod': 'MIXED',
      'customerId': '1',
    });
    await repo.invoices(access(), month, search: '  ');
    expect(
      Uri.parse(f.transport.calls.last.path).queryParameters.containsKey('q'),
      isFalse,
    );
    expect(InvoiceSort.values.map((s) => s.api).toSet(), {
      'date,desc',
      'date,asc',
      'number,asc',
      'total,desc',
      'total,asc',
    });
    expect(
      f.transport.calls
          .where((c) => c.path.startsWith('manager/'))
          .every((c) => c.method == 'GET' && c.body == null),
      isTrue,
    );
  });
  test(
    'detail independently pages lines and returns through one frozen endpoint',
    () async {
      final f = await salesAuth();
      addTearDown(f.auth.dispose);
      final result = await SalesRepository(
        f.auth.repository.client,
      ).detail(access(), 1, page: 1, size: 1, returnsPage: 2, returnsSize: 1);
      expect(result.items.items.single.id, 2);
      expect(result.returns.items.single.id, 3);
      expect(result.returns.items.single.date, '2026-11-05T12:00:00');
      expect(result.invoice.returnedAmount, '9.000');
      expect(
        f.transport.calls.last.path,
        'manager/invoices/1?page=1&size=1&returnsPage=2&returnsSize=1',
      );
    },
  );
  test('permission and input failures never send business requests', () async {
    final f = await salesAuth();
    addTearDown(f.auth.dispose);
    final repo = SalesRepository(f.auth.repository.client);
    f.transport.calls.clear();
    final invalid = <Future<Object?>>[
      repo.report(access(['SALES_VIEW']), month),
      repo.invoices(access([]), month),
      repo.detail(access([]), 1),
      repo.invoices(access(), month, page: -1),
      repo.invoices(access(), month, page: 10001),
      repo.invoices(access(), month, size: 0),
      repo.invoices(access(), month, size: 101),
      repo.invoices(access(), month, customerId: 0),
      repo.invoices(access(), month, search: 'x' * 101),
      repo.detail(access(), 2147483648),
      repo.detail(access(), 0),
      repo.detail(access(), 1, returnsPage: 10001),
      repo.detail(access(), 1, returnsSize: 0),
    ];
    await Future.wait(
      invalid.map((f) => expectLater(f, throwsA(isA<AppFailure>()))),
    );
    expect(f.transport.calls, isEmpty);
  });
  for (final corruption in [
    'wrong-id',
    'item-page',
    'return-page',
    'duplicate-lines',
    'duplicate-returns',
    'detail-cutoff',
    'list-cutoff',
  ]) {
    test('malformed document or pagination rejected: $corruption', () async {
      final f = await salesAuth();
      addTearDown(f.auth.dispose);
      f.transport.handler = (_, p, _, _) async {
        if (corruption == 'list-cutoff') {
          return inventoryPage([
            {...invoiceJson(1), 'returnCutoffDate': '2026-10-30'},
          ]);
        }
        final raw = detailInvoiceJson();
        if (corruption == 'wrong-id') {
          raw['invoice'] = invoiceJson(2, detail: true);
        }
        if (corruption == 'detail-cutoff') raw['invoice'] = invoiceJson(1);
        if (corruption == 'item-page') {
          raw['items'] = inventoryPage([], page: 1);
        }
        if (corruption == 'return-page') {
          raw['returns'] = inventoryPage([], page: 1);
        }
        if (corruption == 'duplicate-lines') {
          raw['items'] = inventoryPage([invoiceLineJson(), invoiceLineJson()]);
        }
        if (corruption == 'duplicate-returns') {
          raw['returns'] = inventoryPage([
            invoiceReturnJson(),
            invoiceReturnJson(),
          ]);
        }
        return raw;
      };
      final repo = SalesRepository(f.auth.repository.client);
      await expectLater(
        corruption == 'list-cutoff'
            ? repo.invoices(
                access(),
                SalesRange.custom('2026-10-01', '2026-10-31'),
              )
            : repo.detail(access(), 1),
        throwsA(isA<AppFailure>()),
      );
    });
  }
}
