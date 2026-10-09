import '../../auth/data/auth_models.dart';
import '../../dashboard/data/dashboard_models.dart';
import '../../inventory/data/inventory_models.dart';
import '../../sales/data/sales_models.dart';
import '../../final_features/data/feature_access.dart';

enum QuotationStatus {
  draft('DRAFT', 'مسودة'),
  sent('SENT', 'مرسلة'),
  accepted('ACCEPTED', 'مقبولة'),
  rejected('REJECTED', 'مرفوضة'),
  expired('EXPIRED', 'منتهية'),
  converted('CONVERTED', 'محوّلة');

  const QuotationStatus(this.api, this.label);
  final String api, label;
}

String quotationLabel(String code) =>
    QuotationStatus.values.where((v) => v.api == code).firstOrNull?.label ??
    'حالة غير معروفة ($code)';

enum QuotationSort {
  newest('date,desc', 'الأحدث'),
  oldest('date,asc', 'الأقدم'),
  number('number,asc', 'الرقم أ–ي'),
  numberDescending('number,desc', 'الرقم ي–أ'),
  total('total,desc', 'الأعلى إجمالياً'),
  totalAscending('total,asc', 'الأقل إجمالياً'),
  expiry('validUntil,asc', 'الأقرب صلاحية'),
  expiryDescending('validUntil,desc', 'الأبعد صلاحية');

  const QuotationSort(this.api, this.label);
  final String api, label;
}

class Quotation {
  Quotation.fromJson(Object? raw, FeatureAccess access) {
    final j = jsonObject(raw);
    id = documentId(j, 'id');
    number = requiredString(j, 'number');
    date = businessTimestamp(j, 'date');
    businessDate = BusinessDay.parse(j['businessDate']);
    validUntil = j['validUntil'] == null
        ? null
        : BusinessDay.parse(j['validUntil']);
    pastValidity = flag(j, 'pastValidity');
    status = requiredString(j, 'status');
    priceType = requiredString(j, 'priceType');
    final customer = jsonObject(j['customer']);
    customerId = customer['id'] == null ? null : documentId(customer, 'id');
    customerCode = nullableText(customer, 'code');
    customerName = nullableText(customer, 'name');
    customerPhone = nullableText(customer, 'phone');
    prospectName = nullableText(j, 'prospectName');
    prospectPhone = nullableText(j, 'prospectPhone');
    final creator = jsonObject(j['creator']);
    creatorId = documentId(creator, 'id');
    creatorName = requiredString(creator, 'name');
    subtotal = decimal(j, 'subtotal');
    discount = decimal(j, 'discount');
    total = decimal(j, 'total');
    sentAt = j['sentAt'] == null ? null : businessTimestamp(j, 'sentAt');
    decidedAt = j['decidedAt'] == null
        ? null
        : businessTimestamp(j, 'decidedAt');
    // Unauthorized link metadata never enters a model, including over-returned data.
    if (access.saleLinks && j['linkedSale'] != null) {
      final sale = jsonObject(j['linkedSale']);
      linkedId = documentId(sale, 'id');
      linkedNumber = requiredString(sale, 'number');
      linkedStatus = requiredString(sale, 'status');
    }
  }
  late final int id, creatorId;
  late final int? customerId;
  late final String number,
      date,
      status,
      priceType,
      creatorName,
      subtotal,
      discount,
      total;
  late final String? customerCode,
      customerName,
      customerPhone,
      prospectName,
      prospectPhone,
      sentAt,
      decidedAt;
  late final BusinessDay businessDate;
  late final BusinessDay? validUntil;
  late final bool pastValidity;
  int? linkedId;
  String? linkedNumber, linkedStatus;
  @override
  String toString() => 'Quotation([PRIVATE])';
}

class QuotationLine {
  QuotationLine.fromJson(Object? raw) {
    final j = jsonObject(raw);
    id = documentId(j, 'id');
    productId = documentId(j, 'productId');
    code = requiredString(j, 'productCode');
    name = requiredString(j, 'name');
    unit = requiredString(j, 'unit');
    quantity = decimal(j, 'quantity');
    unitPrice = decimal(j, 'unitPrice');
    discount = decimal(j, 'discount');
    total = decimal(j, 'total');
  }
  late final int id, productId;
  late final String code, name, unit, quantity, unitPrice, discount, total;
  @override
  String toString() => 'QuotationLine([PRIVATE])';
}

class QuotationQuery {
  QuotationQuery({
    this.range = const MovementRange.preset(MovementPeriod.month),
    this.search = '',
    this.status,
    this.customerId,
    this.pastValidity,
    this.sort = QuotationSort.newest,
  });
  MovementRange range;
  String search;
  QuotationStatus? status;
  int? customerId;
  bool? pastValidity;
  QuotationSort sort;
}
