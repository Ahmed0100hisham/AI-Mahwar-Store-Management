import '../../../core/errors/app_failure.dart';
import '../../../core/network/api_client.dart';
import '../../auth/data/auth_models.dart';
import 'dashboard_access.dart';
import 'dashboard_models.dart';

class DashboardRepository {
  DashboardRepository(this.client);
  final ApiClient client;
  Future<DashboardData> load(
    DashboardAccess access,
    DashboardPeriod period,
  ) async {
    if (!access.dashboard) throw AppFailure.http(403, null);
    final overview = DashboardOverview.fromJson(
      await client.send('GET', 'manager/dashboard'),
      access,
    );
    final range = period.range(overview.businessDate);
    final paths = <String, String>{
      'daily': 'manager/daily-summary?date=${overview.businessDate.iso}',
      if (access.sales)
        'top': 'manager/sales/top-products?${range.query}&limit=5',
      if (access.sales)
        'trend': 'manager/sales/trend?${range.query}&grouping=daily',
      if (access.inventory)
        'slow': 'manager/sales/slow-products?days=30&page=0&size=5',
      if (access.inventory) 'low': 'manager/inventory/low-stock?page=0&size=5',
      if (period != DashboardPeriod.today && access.sales)
        'sales': 'manager/sales/summary?${range.query}',
      if (period != DashboardPeriod.today && access.expenses)
        'expenses': 'manager/expenses/summary?${range.query}',
      if (period != DashboardPeriod.today && access.cash)
        'cash': 'manager/cashbox/summary?${range.query}',
    };
    final results = await Future.wait(
      paths.values.map((path) => client.send('GET', path)),
    );
    final response = Map<String, Object?>.fromIterables(paths.keys, results);
    final daily = DailySummary.fromJson(response['daily'], access);
    if (daily.date.iso != overview.businessDate.iso) throw AppFailure.malformed;
    final sales = response.containsKey('sales')
        ? SalesSummary.fromJson(response['sales'], access)
        : period == DashboardPeriod.today
        ? daily.sales
        : null;
    final expenses = response.containsKey('expenses')
        ? ExpenseSummary.fromJson(response['expenses'])
        : period == DashboardPeriod.today
        ? daily.expenses
        : null;
    final cash = response.containsKey('cash')
        ? CashSummary.fromJson(response['cash'])
        : period == DashboardPeriod.today
        ? daily.cash
        : null;
    sales?.range.requireMatch(range);
    expenses?.range.requireMatch(range);
    cash?.range.requireMatch(range);
    List<TopProduct>? top;
    List<SalesTrendPoint>? trend;
    if (access.sales) {
      final j = jsonObject(response['top']);
      BusinessRange.fromJson(j['range']).requireMatch(range);
      if (count(j, 'limit') != 5) throw AppFailure.malformed;
      top = rows(j, 'items', (raw) => TopProduct.fromJson(raw, access));
      if (top.length > 5) throw AppFailure.malformed;
      final t = jsonObject(response['trend']);
      BusinessRange.fromJson(t['range']).requireMatch(range);
      if (t['grouping'] != 'daily') throw AppFailure.malformed;
      trend = rows(t, 'buckets', SalesTrendPoint.fromJson);
      // Daily trend is zero-filled by the API; missing/duplicate days are malformed.
      final expectedDays =
          range.to.calendar.difference(range.from.calendar).inDays + 1;
      if (trend.length != expectedDays) throw AppFailure.malformed;
      for (var i = 0; i < trend.length; i++) {
        if (trend[i].day.iso !=
            BusinessDay.calendar(range.from.calendar.add(Duration(days: i)))
                .iso) {
          throw AppFailure.malformed;
        }
      }
    }
    return DashboardData(
      overview: overview,
      daily: daily,
      range: range,
      period: period,
      sales: sales,
      expenses: expenses,
      cash: cash,
      top: top,
      trend: trend,
      slow: access.inventory
          ? DashboardPage<SlowProduct>.fromJson(
              response['slow'],
              (raw) => SlowProduct.fromJson(raw, access),
            )
          : null,
      lowStock: access.inventory
          ? DashboardPage<LowStockProduct>.fromJson(
              response['low'],
              LowStockProduct.fromJson,
            )
          : null,
    );
  }
}
