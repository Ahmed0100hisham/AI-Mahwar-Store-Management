import '../../../core/errors/app_failure.dart';
import '../../auth/data/auth_models.dart';
import '../../dashboard/data/dashboard_models.dart';
import '../../inventory/data/inventory_models.dart'
    show InventoryPage, MovementRange, MovementPeriod, nullableText, flag;
import '../../sales/data/sales_models.dart' show businessTimestamp, documentId;
import 'party_access.dart';

typedef PartyRange = MovementRange;
typedef PartyPeriod = MovementPeriod;

enum PartyMode { all, outstanding }

enum PartySort {
  name('الاسم أ–ي', 'name,asc'),
  nameDescending('الاسم ي–أ', 'name,desc'),
  code('الكود تصاعدياً', 'code,asc'),
  codeDescending('الكود تنازلياً', 'code,desc'),
  balance('الرصيد الأقل', 'balance,asc'),
  balanceDescending('الرصيد الأعلى', 'balance,desc');

  const PartySort(this.label, this.api);
  final String label, api;
  bool get financial => this == balance || this == balanceDescending;
}

class PartyProfile {
  PartyProfile.fromJson(Object? raw, PartyAccess access) {
    final j = jsonObject(raw);
    id = documentId(j, 'id');
    code = requiredString(j, 'code');
    name = requiredString(j, 'name');
    phone = nullableText(j, 'phone');
    area = nullableText(j, 'area');
    active = flag(j, 'active');
    balance = redactedDecimal(j, 'balance', access.financial);
  }
  late final int id;
  late final String code, name;
  late final String? phone, area, balance;
  late final bool active;
  @override
  String toString() => 'PartyProfile([PRIVATE])';
}

class PartyListing {
  PartyListing.fromJson(
    Object? raw,
    PartyAccess access, {
    required int page,
    required int size,
    required PartyMode mode,
  }) {
    final j = jsonObject(raw);
    entries = InventoryPage<PartyProfile>.fromJson(
      j['page'],
      (item) => PartyProfile.fromJson(item, access),
      expectedPage: page,
      expectedSize: size,
    );
    if (entries.items.map((item) => item.id).toSet().length !=
        entries.items.length) {
      throw AppFailure.malformed;
    }
    // Normal profile lists have no authoritative debt aggregate.
    totalOutstanding = redactedDecimal(
      j,
      'totalOutstanding',
      access.financial && mode == PartyMode.outstanding,
    );
  }
  late final InventoryPage<PartyProfile> entries;
  late final String? totalOutstanding;
  @override
  String toString() => 'PartyListing([PRIVATE])';
}

String accountTypeLabel(String type) => switch (type) {
  'OPENING_BALANCE' => 'رصيد افتتاحي',
  'SALE' => 'فاتورة بيع',
  'SALE_RETURN' => 'مرتجع بيع',
  'PURCHASE' => 'فاتورة شراء',
  'PURCHASE_RETURN' => 'مرتجع شراء',
  'PAYMENT' => 'دفعة',
  'ADJUSTMENT' => 'تسوية',
  _ => type,
};

class PartyEntry {
  PartyEntry.fromJson(Object? raw) {
    final j = jsonObject(raw);
    date = businessTimestamp(j, 'date');
    type = requiredString(j, 'type');
    reference = nullableText(j, 'reference');
    debit = decimal(j, 'debit');
    credit = decimal(j, 'credit');
    runningBalance = decimal(j, 'runningBalance');
  }
  late final String date, type, debit, credit, runningBalance;
  late final String? reference;
  @override
  String toString() => 'PartyEntry([PRIVATE])';
}

class PartyAccount {
  PartyAccount.fromJson(
    Object? raw,
    PartyAccess access, {
    required int id,
    required int page,
    required int size,
    required PartyRange requested,
  }) {
    // An unauthorized account is never parsed or retained, even if over-returned.
    if (!access.financial) throw AppFailure.http(403, null);
    final j = jsonObject(raw);
    partyId = documentId(j, 'partyId');
    if (partyId != id) throw AppFailure.malformed;
    range = BusinessRange.fromJson(j['range']);
    PartyRange.custom(range.from.iso, range.to.iso);
    if (requested.period == null) {
      range.requireMatch(
        BusinessRange(
          BusinessDay.parse(requested.from),
          BusinessDay.parse(requested.to),
        ),
      );
    }
    opening = decimal(j, 'openingBalance');
    debit = decimal(j, 'totalDebit');
    credit = decimal(j, 'totalCredit');
    closing = decimal(j, 'closingBalance');
    entries = InventoryPage<PartyEntry>.fromJson(
      j['entries'],
      PartyEntry.fromJson,
      expectedPage: page,
      expectedSize: size,
    );
    String? previous;
    for (final entry in entries.items) {
      final day = entry.date.substring(0, 10);
      if (day.compareTo(range.from.iso) < 0 ||
          day.compareTo(range.to.iso) > 0 ||
          (previous != null && entry.date.compareTo(previous) < 0)) {
        throw AppFailure.malformed;
      }
      previous = entry.date;
    }
  }
  late final int partyId;
  late final BusinessRange range;
  late final String opening, debit, credit, closing;
  late final InventoryPage<PartyEntry> entries;
  @override
  String toString() => 'PartyAccount([PRIVATE])';
}
