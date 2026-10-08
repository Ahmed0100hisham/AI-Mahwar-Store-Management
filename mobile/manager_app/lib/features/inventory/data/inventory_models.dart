import '../../../core/errors/app_failure.dart';
import '../../auth/data/auth_models.dart';
import '../../dashboard/data/dashboard_models.dart'
    show BusinessDay, count, decimal, redactedDecimal;
import 'inventory_access.dart';

String? nullableText(Map<String, dynamic> j, String key) {
  final value = j[key];
  if (value != null && value is! String) throw AppFailure.malformed;
  return value as String?;
}

bool flag(Map<String, dynamic> j, String key) {
  if (j[key] is! bool) throw AppFailure.malformed;
  return j[key] as bool;
}

int positiveId(Map<String, dynamic> j) {
  final id = count(j, 'id');
  if (id == 0) throw AppFailure.malformed;
  return id;
}

BigInt _scaled(String value) => BigInt.parse(value.replaceAll('.', ''));

class InventoryProduct {
  InventoryProduct._({
    required this.id,
    required this.code,
    required this.name,
    required this.unit,
    required this.quantity,
    required this.minimum,
    required this.active,
    required this.lowStock,
    this.barcode,
    this.nameEn,
    this.category,
    this.brand,
    this.salePrice,
    this.wholesalePrice,
    this.cost,
  });
  factory InventoryProduct.fromJson(
    Object? raw,
    InventoryAccess access, {
    bool low = false,
    bool detail = false,
  }) {
    final j = jsonObject(raw);
    final quantity = decimal(j, 'quantity'),
        minimum = decimal(j, 'minimumStock');
    final active = low ? true : flag(j, 'active');
    return InventoryProduct._(
      id: positiveId(j),
      code: requiredString(j, 'code'),
      name: requiredString(j, low ? 'name' : 'nameAr'),
      unit: requiredString(j, 'unit'),
      quantity: quantity,
      minimum: minimum,
      active: active,
      lowStock:
          low ||
          (detail
              ? flag(j, 'lowStock')
              : active && _scaled(quantity) <= _scaled(minimum)),
      barcode: low ? null : nullableText(j, 'barcode'),
      nameEn: low ? null : nullableText(j, 'nameEn'),
      category: low ? null : nullableText(j, 'category'),
      brand: low ? null : nullableText(j, 'brand'),
      salePrice: low ? null : decimal(j, 'salePrice'),
      wholesalePrice: low || detail ? null : decimal(j, 'wholesalePrice'),
      cost: redactedDecimal(
        j,
        low || detail ? 'purchaseCost' : 'purchasePrice',
        access.cost,
      ),
    );
  }
  final int id;
  final String code, name, unit, quantity, minimum;
  final bool active, lowStock;
  final String? barcode,
      nameEn,
      category,
      brand,
      salePrice,
      wholesalePrice,
      cost;
  String get stockLabel => !active
      ? 'غير نشط'
      : _scaled(quantity) <= BigInt.zero
      ? 'نفد المخزون'
      : lowStock
      ? 'مخزون منخفض'
      : 'متوفر';
  @override
  String toString() => 'InventoryProduct([PRIVATE])';
}

class InventoryPage<T> {
  InventoryPage.fromJson(
    Object? raw,
    T Function(Object?) parse, {
    required int expectedPage,
    required int expectedSize,
  }) {
    final j = jsonObject(raw);
    page = count(j, 'page');
    size = count(j, 'size');
    total = count(j, 'totalItems');
    pages = count(j, 'totalPages');
    if (page != expectedPage ||
        size != expectedSize ||
        page > 10000 ||
        size < 1 ||
        size > 100 ||
        pages != (total + size - 1) ~/ size ||
        j['items'] is! List ||
        (j['items'] as List).length > size) {
      throw AppFailure.malformed;
    }
    items = List.unmodifiable((j['items'] as List).map(parse));
    // Count and page are separate server statements: do not demand a transactional snapshot.
  }
  late final List<T> items;
  late final int page, size, total, pages;
  @override
  String toString() => 'InventoryPage([PRIVATE])';
}

class InventoryMovement {
  InventoryMovement.fromJson(Object? raw, InventoryAccess access) {
    final j = jsonObject(raw);
    date = requiredString(j, 'date');
    final match = RegExp(
      r'^(\d{4}-\d{2}-\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.\d{1,9})?)?$',
    ).firstMatch(date);
    if (match == null ||
        int.parse(match[2]!) > 23 ||
        int.parse(match[3]!) > 59 ||
        int.parse(match[4] ?? '0') > 59) {
      throw AppFailure.malformed;
    }
    BusinessDay.parse(match[1]);
    type = requiredString(j, 'type');
    typeName = requiredString(j, 'typeName');
    quantity = decimal(j, 'quantity');
    before = decimal(j, 'before');
    after = decimal(j, 'after');
    unitCost = redactedDecimal(j, 'unitCost', access.cost);
  }
  late final String date, type, typeName, quantity, before, after;
  late final String? unitCost;
  @override
  String toString() => 'InventoryMovement([PRIVATE])';
}

enum InventoryMode { all, low }

enum InventorySort {
  name('الاسم أ–ي', 'name,asc'),
  nameDescending('الاسم ي–أ', 'name,desc'),
  code('كود المنتج', 'code,asc'),
  quantity('الأقل كمية', 'quantity,asc'),
  quantityDescending('الأكثر كمية', 'quantity,desc');

  const InventorySort(this.label, this.api);
  final String label, api;
}

enum MovementPeriod {
  today('اليوم', 'today'),
  week('هذا الأسبوع', 'this_week'),
  month('هذا الشهر', 'this_month');

  const MovementPeriod(this.label, this.api);
  final String label, api;
}

class MovementRange {
  const MovementRange.preset(this.period) : from = null, to = null;
  factory MovementRange.custom(String from, String to) {
    final first = BusinessDay.parse(from), last = BusinessDay.parse(to);
    final days = last.calendar.difference(first.calendar).inDays + 1;
    if (days < 1 || days > 366) {
      throw const AppFailure(
        'DATE_RANGE',
        'اختر فترة صحيحة لا تتجاوز 366 يوماً.',
      );
    }
    return MovementRange._(first.iso, last.iso);
  }
  const MovementRange._(this.from, this.to) : period = null;
  final MovementPeriod? period;
  final String? from, to;
  Map<String, String> get query => period == null
      ? {'period': 'custom', 'from': from!, 'to': to!}
      : {'period': period!.api};
  String get label => period?.label ?? '\u2066$from\u2069 — \u2066$to\u2069';
}
