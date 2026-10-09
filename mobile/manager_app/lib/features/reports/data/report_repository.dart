import '../../../core/errors/app_failure.dart';
import '../../../core/network/api_client.dart';
import '../../auth/data/auth_models.dart';
import '../../dashboard/data/dashboard_models.dart';
import '../../inventory/data/inventory_models.dart';
import '../../final_features/data/feature_access.dart';
import '../../final_features/data/feature_query.dart';
import '../../final_features/state/feature_state.dart';

class ExpenseCategory {
  ExpenseCategory.fromJson(Object? raw) {
    final j = jsonObject(raw);
    name = requiredString(j, 'category');
    amount = decimal(j, 'amount');
    entries = count(j, 'count');
  }
  late final String name, amount;
  late final int entries;
  @override
  String toString() => 'ExpenseCategory([PRIVATE])';
}

class ExpenseReport {
  ExpenseReport(this.summary, this.categories);
  final ExpenseSummary summary;
  final List<ExpenseCategory> categories;
  @override
  String toString() => 'ExpenseReport([PRIVATE])';
}

class ReportRepository {
  ReportRepository(this.client);
  final ApiClient client;
  Future<InventorySummary> inventory(FeatureAccess access) async {
    requireFeature(access.analytics?.inventory ?? false);
    return InventorySummary.fromJson(
      await client.send('GET', 'manager/inventory/summary'),
      access.analytics!,
    );
  }

  Future<ExpenseReport> expenses(
    FeatureAccess access,
    MovementRange range,
  ) async {
    requireFeature(access.analytics?.expenses ?? false);
    final j = jsonObject(
      await client.send(
        'GET',
        featurePath('manager/expenses/summary', range.query),
      ),
    );
    final summary = ExpenseSummary.fromJson(j);
    checkFeatureRange(range, summary.range);
    return ExpenseReport(
      summary,
      rows(j, 'categories', ExpenseCategory.fromJson),
    );
  }

  Future<CashSummary> cash(FeatureAccess access, MovementRange range) async {
    requireFeature(access.analytics?.cash ?? false);
    final result = CashSummary.fromJson(
      await client.send(
        'GET',
        featurePath('manager/cashbox/summary', range.query),
      ),
    );
    checkFeatureRange(range, result.range);
    return result;
  }

  Future<DailySummary> daily(FeatureAccess access, String? date) async {
    requireFeature(access.analytics?.dashboard ?? false);
    if (date != null) BusinessDay.parse(date);
    final result = DailySummary.fromJson(
      await client.send(
        'GET',
        featurePath('manager/daily-summary', {'date': ?date}),
      ),
      access.analytics!,
    );
    if (date != null && result.date.iso != date) throw AppFailure.malformed;
    return result;
  }

  Future<FeaturePage<SlowProduct>> slow(
    FeatureAccess access,
    int days,
    String search,
    int page,
    int size,
  ) async {
    requireFeature(access.analytics?.inventory ?? false);
    if (days < 1 || days > 3650) throw AppFailure.malformed;
    final q = featureSearch(search);
    final result = InventoryPage<SlowProduct>.fromJson(
      await client.send(
        'GET',
        featurePath('manager/sales/slow-products', {
          ...featurePage(page, size),
          'days': '$days',
          'sort': 'lastSale,asc',
          if (q.isNotEmpty) 'q': q,
        }),
      ),
      (v) => SlowProduct.fromJson(v, access.analytics!),
      expectedPage: page,
      expectedSize: size,
    );
    return FeaturePage(result);
  }
}
