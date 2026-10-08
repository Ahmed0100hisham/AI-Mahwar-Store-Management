import '../../../core/errors/app_failure.dart';
import '../../auth/data/auth_models.dart';
import 'dashboard_access.dart';

/// Calendar-only accounting date; no device timezone or UTC/local conversions.
class BusinessDay implements Comparable<BusinessDay> {
  BusinessDay._(this.iso, this.calendar);
  factory BusinessDay.parse(Object? value) {
    if (value is! String || !RegExp(r'^\d{4}-\d{2}-\d{2}$').hasMatch(value)) {
      throw AppFailure.malformed;
    }
    final parts = value.split('-').map(int.parse).toList();
    final date = DateTime.utc(parts[0], parts[1], parts[2]);
    if (date.year < 1900 ||
        date.year > 9998 ||
        date.year != parts[0] ||
        date.month != parts[1] ||
        date.day != parts[2]) {
      throw AppFailure.malformed;
    }
    return BusinessDay._(value, date);
  }
  factory BusinessDay.calendar(DateTime date) => BusinessDay.parse(
    '${date.year.toString().padLeft(4, '0')}-${date.month.toString().padLeft(2, '0')}-${date.day.toString().padLeft(2, '0')}',
  );
  final String iso;
  final DateTime calendar;
  @override
  int compareTo(BusinessDay other) => iso.compareTo(other.iso);
}

enum DashboardPeriod {
  today('اليوم'),
  thisWeek('هذا الأسبوع'),
  thisMonth('هذا الشهر');

  const DashboardPeriod(this.label);
  final String label;
  BusinessRange range(BusinessDay businessDay) {
    final day = businessDay.calendar;
    final from = switch (this) {
      today => day,
      thisWeek => day.subtract(Duration(days: day.weekday % 7)),
      thisMonth => DateTime.utc(day.year, day.month, 1),
    };
    final end = this == thisMonth
        ? DateTime.utc(day.year, day.month + 1, 0)
        : day;
    return BusinessRange(BusinessDay.calendar(from), BusinessDay.calendar(end));
  }
}

class BusinessRange {
  BusinessRange(this.from, this.to);
  factory BusinessRange.fromJson(Object? value) {
    final j = jsonObject(value);
    final range = BusinessRange(
      BusinessDay.parse(j['from']),
      BusinessDay.parse(j['to']),
    );
    if (range.from.compareTo(range.to) > 0) throw AppFailure.malformed;
    return range;
  }
  final BusinessDay from, to;
  String get query => 'from=${from.iso}&to=${to.iso}';
  void requireMatch(BusinessRange expected) {
    if (from.iso != expected.from.iso || to.iso != expected.to.iso) {
      throw AppFailure.malformed;
    }
  }
}

String decimal(Map<String, dynamic> j, String key) {
  final value = j[key];
  if (value is! String || !RegExp(r'^-?\d+\.\d{3}$').hasMatch(value)) {
    throw AppFailure.malformed;
  }
  return value;
}

String? redactedDecimal(Map<String, dynamic> j, String key, bool allowed) {
  // Unauthorized fields never enter the typed client model, even if over-returned.
  if (!allowed || !j.containsKey(key)) return null;
  return decimal(j, key);
}

int count(Map<String, dynamic> j, String key) {
  final value = j[key];
  if (value is! int || value < 0) throw AppFailure.malformed;
  return value;
}

List<T> rows<T>(Map<String, dynamic> j, String key, T Function(Object?) parse) {
  final value = j[key];
  if (value is! List) throw AppFailure.malformed;
  return List.unmodifiable(value.map(parse));
}

class OverviewMetric {
  OverviewMetric(this.name, this.value);
  final String name, value;
  bool get isMoney => name != 'TODAY_INVOICES' && name != 'LOW_STOCK';
}

class DashboardOverview {
  DashboardOverview(this.businessDate, this.metrics);
  factory DashboardOverview.fromJson(Object? value, DashboardAccess access) {
    final j = jsonObject(value);
    if (j['metrics'] is! List) throw AppFailure.malformed;
    final metrics = <String, OverviewMetric>{};
    for (final raw in j['metrics'] as List) {
      final item = jsonObject(raw);
      final name = requiredString(item, 'name');
      if (!access.metric(name)) {
        continue; // Compatible unknown metrics are ignored.
      }
      if (metrics.containsKey(name)) throw AppFailure.malformed;
      final money = name != 'TODAY_INVOICES' && name != 'LOW_STOCK';
      final text = money
          ? decimal(item, 'value')
          : requiredString(item, 'value');
      if (!money && !RegExp(r'^\d+$').hasMatch(text)) {
        throw AppFailure.malformed;
      }
      if (name == 'TODAY_SALES' || name == 'MONTH_SALES') {
        decimal(item, 'gross');
        decimal(item, 'returns');
      }
      metrics[name] = OverviewMetric(name, text);
    }
    return DashboardOverview(
      BusinessDay.parse(j['businessDate']),
      Map.unmodifiable(metrics),
    );
  }
  final BusinessDay businessDate;
  final Map<String, OverviewMetric> metrics;
}

class SalesSummary {
  SalesSummary.fromJson(Object? value, DashboardAccess access) {
    final j = jsonObject(value);
    range = BusinessRange.fromJson(j['range']);
    grossSales = decimal(j, 'grossSales');
    returns = decimal(j, 'returns');
    netSales = decimal(j, 'netSales');
    averageInvoice = decimal(j, 'averageInvoice');
    invoiceCount = count(j, 'invoiceCount');
    returnCount = count(j, 'returnCount');
    if (access.profit && j.containsKey('profit')) {
      final profit = jsonObject(j['profit']);
      netProfit = decimal(profit, 'netProfit');
      // Historical cost is deliberately not retained or exposed by this dashboard.
    }
  }
  late final BusinessRange range;
  late final String grossSales, returns, netSales, averageInvoice;
  late final int invoiceCount, returnCount;
  String? netProfit;
}

class ExpenseSummary {
  ExpenseSummary.fromJson(Object? value) {
    final j = jsonObject(value);
    range = BusinessRange.fromJson(j['range']);
    total = decimal(j, 'total');
    entries = count(j, 'count');
    rows(j, 'categories', (raw) {
      final category = jsonObject(raw);
      requiredString(category, 'category');
      decimal(category, 'amount');
      count(category, 'count');
      return true;
    });
  }
  late final BusinessRange range;
  late final String total;
  late final int entries;
}

class CashSummary {
  CashSummary.fromJson(Object? value) {
    final j = jsonObject(value);
    range = BusinessRange.fromJson(j['range']);
    opening = decimal(j, 'openingBalance');
    incoming = decimal(j, 'totalIn');
    outgoing = decimal(j, 'totalOut');
    movement = decimal(j, 'netMovement');
    closing = decimal(j, 'closingBalance');
  }
  late final BusinessRange range;
  late final String opening, incoming, outgoing, movement, closing;
}

class InventorySummary {
  InventorySummary.fromJson(Object? value, DashboardAccess access) {
    final j = jsonObject(value);
    active = count(j, 'activeProducts');
    low = count(j, 'lowStockProducts');
    out = count(j, 'outOfStockProducts');
    valueAtCost = redactedDecimal(j, 'inventoryValue', access.cost);
  }
  late final int active, low, out;
  late final String? valueAtCost;
}

class DailySummary {
  DailySummary.fromJson(Object? value, DashboardAccess access) {
    final j = jsonObject(value);
    date = BusinessDay.parse(j['date']);
    if (access.sales && j.containsKey('sales')) {
      sales = SalesSummary.fromJson(j['sales'], access);
    }
    if (access.expenses && j.containsKey('expenses')) {
      expenses = ExpenseSummary.fromJson(j['expenses']);
    }
    if (access.cash && j.containsKey('cashbox')) {
      cash = CashSummary.fromJson(j['cashbox']);
    }
    if (access.inventory && j.containsKey('inventorySnapshot')) {
      final snapshot = jsonObject(j['inventorySnapshot']);
      inventoryDate = BusinessDay.parse(snapshot['businessDate']);
      inventory = InventorySummary.fromJson(snapshot['inventory'], access);
    }
    final expected = BusinessRange(date, date);
    sales?.range.requireMatch(expected);
    expenses?.range.requireMatch(expected);
    cash?.range.requireMatch(expected);
  }
  late final BusinessDay date;
  SalesSummary? sales;
  ExpenseSummary? expenses;
  CashSummary? cash;
  InventorySummary? inventory;
  BusinessDay? inventoryDate;
}

class TopProduct {
  TopProduct.fromJson(Object? value, DashboardAccess access) {
    final j = jsonObject(value);
    code = requiredString(j, 'code');
    name = requiredString(j, 'name');
    unit = requiredString(j, 'unit');
    decimal(j, 'grossQuantity');
    decimal(j, 'returnedQuantity');
    quantity = decimal(j, 'netQuantity');
    revenue = decimal(j, 'netRevenue');
    profit = redactedDecimal(j, 'grossProfit', access.profit);
  }
  late final String code, name, unit, quantity, revenue;
  late final String? profit;
}

class SlowProduct {
  SlowProduct.fromJson(Object? value, DashboardAccess access) {
    final j = jsonObject(value);
    code = requiredString(j, 'code');
    name = requiredString(j, 'name');
    unit = requiredString(j, 'unit');
    quantity = decimal(j, 'quantity');
    lastSale = j['lastSale'] == null ? null : BusinessDay.parse(j['lastSale']);
    if (!j.containsKey('lastSale') ||
        !j.containsKey('daysSinceLastSale') ||
        (j['daysSinceLastSale'] != null &&
            (j['daysSinceLastSale'] is! int ||
                (j['daysSinceLastSale'] as int) < 0))) {
      throw AppFailure.malformed;
    }
    daysSinceLastSale = j['daysSinceLastSale'] as int?;
    valueAtCost = redactedDecimal(j, 'inventoryValue', access.cost);
  }
  late final String code, name, unit, quantity;
  late final BusinessDay? lastSale;
  late final int? daysSinceLastSale;
  late final String? valueAtCost;
}

class LowStockProduct {
  LowStockProduct.fromJson(Object? value) {
    final j = jsonObject(value);
    id = count(j, 'id');
    if (id == 0) throw AppFailure.malformed;
    code = requiredString(j, 'code');
    name = requiredString(j, 'name');
    unit = requiredString(j, 'unit');
    quantity = decimal(j, 'quantity');
    minimum = decimal(j, 'minimumStock');
    // purchaseCost is intentionally not mapped: the stock alert needs quantities only.
  }
  late final int id;
  late final String code, name, unit, quantity, minimum;
}

class DashboardPage<T> {
  DashboardPage.fromJson(Object? value, T Function(Object?) parse) {
    final j = jsonObject(value);
    items = rows(j, 'items', parse);
    total = count(j, 'totalItems');
    page = count(j, 'page');
    size = count(j, 'size');
    pages = count(j, 'totalPages');
    if (page != 0 ||
        size != 5 ||
        items.length > size ||
        pages != (total == 0 ? 0 : (total + size - 1) ~/ size)) {
      throw AppFailure.malformed;
    }
  }
  late final List<T> items;
  late final int total, page, size, pages;
}

class SalesTrendPoint {
  SalesTrendPoint.fromJson(Object? value) {
    final j = jsonObject(value);
    day = BusinessDay.parse(j['bucket']);
    netSales = decimal(j, 'netSales');
    decimal(j, 'grossSales');
    decimal(j, 'returns');
    invoices = count(j, 'invoiceCount');
  }
  late final BusinessDay day;
  late final String netSales;
  late final int invoices;
}

class DashboardData {
  DashboardData({
    required this.overview,
    required this.daily,
    required this.range,
    required this.period,
    this.sales,
    this.expenses,
    this.cash,
    this.top,
    this.slow,
    this.lowStock,
    this.trend,
  });
  final DashboardOverview overview;
  final DailySummary daily;
  final BusinessRange range;
  final DashboardPeriod period;
  final SalesSummary? sales;
  final ExpenseSummary? expenses;
  final CashSummary? cash;
  final List<TopProduct>? top;
  final DashboardPage<SlowProduct>? slow;
  final DashboardPage<LowStockProduct>? lowStock;
  final List<SalesTrendPoint>? trend;
  @override
  String toString() => 'DashboardData([PRIVATE])';
}
