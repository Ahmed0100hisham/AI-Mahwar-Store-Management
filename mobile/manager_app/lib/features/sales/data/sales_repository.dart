import '../../../core/errors/app_failure.dart';
import '../../../core/network/api_client.dart';
import '../../auth/data/auth_models.dart';
import '../../dashboard/data/dashboard_access.dart';
import '../../dashboard/data/dashboard_models.dart';
import '../../inventory/data/inventory_models.dart' show InventoryPage;
import 'sales_access.dart';
import 'sales_models.dart';

class SalesRepository {
  SalesRepository(this.client);
  final ApiClient client;
  void _page(int page, int size) {
    if (page < 0 || page > 10000 || size < 1 || size > 100) {
      throw AppFailure.malformed;
    }
  }

  void _id(int id) {
    if (id < 1 || id > 2147483647) {
      throw AppFailure.malformed;
    }
  }

  String _path(String path, Map<String, String> query) =>
      Uri(path: path, queryParameters: query).toString();
  Future<SalesReport> report(SalesAccess access, SalesRange range) async {
    if (!access.reports) {
      throw AppFailure.http(403, null);
    }
    final permissions = DashboardAccess(access.user!);
    final summary = SalesSummary.fromJson(
      await client.send('GET', _path('manager/sales/summary', range.query)),
      permissions,
    );
    // The server's returned range, rather than a device date, anchors the batch.
    SalesRange.custom(summary.range.from.iso, summary.range.to.iso);
    if (range.period == null) {
      summary.range.requireMatch(
        BusinessRange(
          BusinessDay.parse(range.from),
          BusinessDay.parse(range.to),
        ),
      );
    }
    final query = {'from': summary.range.from.iso, 'to': summary.range.to.iso};
    final response = await Future.wait([
      client.send(
        'GET',
        _path('manager/sales/trend', {...query, 'grouping': 'daily'}),
      ),
      client.send(
        'GET',
        _path('manager/sales/top-products', {...query, 'limit': '5'}),
      ),
    ], eagerError: true);
    final trendRaw = jsonObject(response[0]), topRaw = jsonObject(response[1]);
    BusinessRange.fromJson(trendRaw['range']).requireMatch(summary.range);
    BusinessRange.fromJson(topRaw['range']).requireMatch(summary.range);
    if (trendRaw['grouping'] != 'daily' || count(topRaw, 'limit') != 5) {
      throw AppFailure.malformed;
    }
    final trend = rows(trendRaw, 'buckets', SalesTrendPoint.fromJson),
        top = rows(
          topRaw,
          'items',
          (raw) => TopProduct.fromJson(raw, permissions),
        );
    final days =
        summary.range.to.calendar
            .difference(summary.range.from.calendar)
            .inDays +
        1;
    if (trend.length != days || top.length > 5) {
      throw AppFailure.malformed;
    }
    for (var i = 0; i < trend.length; i++) {
      if (trend[i].day.iso !=
          BusinessDay.calendar(
            summary.range.from.calendar.add(Duration(days: i)),
          ).iso) {
        throw AppFailure.malformed;
      }
    }
    return SalesReport(summary, trend, top);
  }

  Future<InventoryPage<SalesInvoice>> invoices(
    SalesAccess access,
    SalesRange range, {
    String search = '',
    InvoiceStatus? status,
    InvoicePayment? payment,
    InvoiceSort sort = InvoiceSort.newest,
    int? customerId,
    int page = 0,
    int size = 20,
  }) async {
    if (!access.invoices) {
      throw AppFailure.http(403, null);
    }
    _page(page, size);
    if (customerId != null) {
      _id(customerId);
    }
    final q = search.trim();
    if (q.length > 100) {
      throw const AppFailure('SEARCH_LENGTH', 'الحد الأقصى للبحث 100 حرف.');
    }
    final raw = await client.send(
      'GET',
      _path('manager/invoices', {
        ...range.query,
        'page': '$page',
        'size': '$size',
        'sort': sort.api,
        if (q.isNotEmpty) 'q': q,
        if (status != null) 'status': status.api,
        if (payment != null) 'paymentMethod': payment.api,
        if (customerId != null) 'customerId': '$customerId',
      }),
    );
    final result = InventoryPage<SalesInvoice>.fromJson(
      raw,
      (item) => SalesInvoice.fromJson(item, access),
      expectedPage: page,
      expectedSize: size,
    );
    _unique(result.items.map((i) => i.id));
    for (final item in result.items) {
      if (range.to != null && item.returnCutoff!.iso != range.to) {
        throw AppFailure.malformed;
      }
    }
    return result;
  }

  Future<InvoiceDetail> detail(
    SalesAccess access,
    int id, {
    int page = 0,
    int size = 20,
    int returnsPage = 0,
    int returnsSize = 20,
  }) async {
    if (!access.invoices) {
      throw AppFailure.http(403, null);
    }
    _id(id);
    _page(page, size);
    _page(returnsPage, returnsSize);
    final raw = jsonObject(
      await client.send(
        'GET',
        _path('manager/invoices/$id', {
          'page': '$page',
          'size': '$size',
          'returnsPage': '$returnsPage',
          'returnsSize': '$returnsSize',
        }),
      ),
    );
    final invoice = SalesInvoice.fromJson(raw['invoice'], access, detail: true);
    if (invoice.id != id) {
      throw AppFailure.malformed;
    }
    final items = InventoryPage<InvoiceLine>.fromJson(
      raw['items'],
      (item) => InvoiceLine.fromJson(item, access),
      expectedPage: page,
      expectedSize: size,
    );
    final returns = InventoryPage<InvoiceReturn>.fromJson(
      raw['returns'],
      InvoiceReturn.fromJson,
      expectedPage: returnsPage,
      expectedSize: returnsSize,
    );
    _unique(items.items.map((i) => i.id));
    _unique(returns.items.map((i) => i.id));
    return InvoiceDetail(invoice, items, returns);
  }

  void _unique(Iterable<int> ids) {
    if (ids.toSet().length != ids.length) {
      throw AppFailure.malformed;
    }
  }
}
