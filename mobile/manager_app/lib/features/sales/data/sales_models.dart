import '../../../core/errors/app_failure.dart';
import '../../auth/data/auth_models.dart';
import '../../dashboard/data/dashboard_models.dart';
import '../../inventory/data/inventory_models.dart'
    show InventoryPage, MovementRange, MovementPeriod, nullableText;
import 'sales_access.dart';

// Reuse released calendar-range validation/presets without a device clock.
typedef SalesRange = MovementRange;
typedef SalesPeriod = MovementPeriod;

int documentId(Map<String, dynamic> j, String key) {
  final value = count(j, key);
  if (value < 1 || value > 2147483647) {
    throw AppFailure.malformed;
  }
  return value;
}

String businessTimestamp(Map<String, dynamic> j, String key) {
  final value = requiredString(j, key);
  final match = RegExp(
    r'^(\d{4}-\d{2}-\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.\d{1,9})?)?$',
  ).firstMatch(value);
  if (match == null ||
      int.parse(match[2]!) > 23 ||
      int.parse(match[3]!) > 59 ||
      int.parse(match[4] ?? '0') > 59) {
    throw AppFailure.malformed;
  }
  BusinessDay.parse(match[1]);
  return value;
}

String invoiceStateLabel(String code) => switch (code) {
  'POSTED' => 'مرحّلة',
  'DRAFT' => 'مسودة',
  'CANCELLED' => 'ملغاة',
  _ => code,
};
String paymentStateLabel(String code) => switch (code) {
  'PAID' => 'مدفوعة',
  'UNPAID' => 'غير مدفوعة',
  'PARTIAL' => 'مدفوعة جزئياً',
  _ => code,
};
String paymentMethodLabel(String code) => switch (code) {
  'CASH' => 'نقدي',
  'KNET' => 'كي نت',
  'CREDIT_CARD' => 'بطاقة ائتمان',
  'BANK_TRANSFER' => 'تحويل بنكي',
  'CHEQUE' => 'شيك',
  'CREDIT' => 'آجل',
  'MIXED' => 'مختلط',
  _ => code,
};
String priceTypeLabel(String code) => switch (code) {
  'RETAIL' => 'تجزئة',
  'WHOLESALE' => 'جملة',
  _ => code,
};

enum InvoiceSort {
  newest('الأحدث أولاً', 'date,desc'),
  oldest('الأقدم أولاً', 'date,asc'),
  number('رقم الفاتورة', 'number,asc'),
  highest('الأعلى إجمالياً', 'total,desc'),
  lowest('الأقل إجمالياً', 'total,asc');

  const InvoiceSort(this.label, this.api);
  final String label, api;
}

enum InvoiceStatus {
  posted('مرحّلة', 'POSTED'),
  draft('مسودة', 'DRAFT'),
  cancelled('ملغاة', 'CANCELLED');

  const InvoiceStatus(this.label, this.api);
  final String label, api;
}

enum InvoicePayment {
  cash('نقدي', 'CASH'),
  knet('كي نت', 'KNET'),
  card('بطاقة ائتمان', 'CREDIT_CARD'),
  bank('تحويل بنكي', 'BANK_TRANSFER'),
  cheque('شيك', 'CHEQUE'),
  credit('آجل', 'CREDIT'),
  mixed('مختلط', 'MIXED');

  const InvoicePayment(this.label, this.api);
  final String label, api;
}

class InvoiceCustomer {
  InvoiceCustomer.fromJson(Object? raw) {
    final j = jsonObject(raw);
    id = documentId(j, 'id');
    code = requiredString(j, 'code');
    name = requiredString(j, 'name');
    phone = nullableText(j, 'phone');
  }
  late final int id;
  late final String code, name;
  late final String? phone;
  @override
  String toString() => 'InvoiceCustomer([PRIVATE])';
}

class SalesInvoice {
  SalesInvoice.fromJson(
    Object? raw,
    SalesAccess access, {
    bool detail = false,
  }) {
    final j = jsonObject(raw);
    id = documentId(j, 'id');
    number = requiredString(j, 'number');
    date = businessTimestamp(j, 'date');
    customer = InvoiceCustomer.fromJson(j['customer']);
    final creator = jsonObject(j['creator']);
    creatorId = documentId(creator, 'id');
    creatorName = requiredString(creator, 'name');
    status = requiredString(j, 'status');
    priceType = requiredString(j, 'priceType');
    paymentMethod = requiredString(j, 'paymentMethod');
    paymentStatus = requiredString(j, 'paymentStatus');
    subtotal = decimal(j, 'subtotal');
    discount = decimal(j, 'discount');
    tax = decimal(j, 'tax');
    total = decimal(j, 'total');
    paid = decimal(j, 'paid');
    remaining = decimal(j, 'remaining');
    returnedAmount = decimal(j, 'returnedAmount');
    refundedAmount = decimal(j, 'refundedAmount');
    netAmount = decimal(j, 'netAmount');
    if (detail) {
      if (j['returnCutoffDate'] != null) {
        throw AppFailure.malformed;
      }
      returnCutoff = null;
    } else {
      returnCutoff = BusinessDay.parse(j['returnCutoffDate']);
    }
    historicalCost = redactedDecimal(j, 'historicalCost', access.invoiceCost);
    grossProfit = redactedDecimal(
      j,
      'grossProfit',
      access.invoiceProfit && status == 'POSTED',
    );
  }
  late final int id, creatorId;
  late final String number,
      date,
      creatorName,
      status,
      priceType,
      paymentMethod,
      paymentStatus;
  late final String subtotal,
      discount,
      tax,
      total,
      paid,
      remaining,
      returnedAmount,
      refundedAmount,
      netAmount;
  late final InvoiceCustomer customer;
  late final BusinessDay? returnCutoff;
  late final String? historicalCost, grossProfit;
  @override
  String toString() => 'SalesInvoice([PRIVATE])';
}

class InvoiceLine {
  InvoiceLine.fromJson(Object? raw, SalesAccess access) {
    final j = jsonObject(raw);
    id = documentId(j, 'id');
    productId = documentId(j, 'productId');
    productCode = requiredString(j, 'productCode');
    name = requiredString(j, 'name');
    unit = requiredString(j, 'unit');
    quantity = decimal(j, 'quantity');
    unitPrice = decimal(j, 'unitPrice');
    discount = decimal(j, 'discount');
    total = decimal(j, 'total');
    returnedQuantity = decimal(j, 'returnedQuantity');
    historicalUnitCost = redactedDecimal(
      j,
      'historicalUnitCost',
      access.invoiceCost,
    );
  }
  late final int id, productId;
  late final String productCode,
      name,
      unit,
      quantity,
      unitPrice,
      discount,
      total,
      returnedQuantity;
  late final String? historicalUnitCost;
  @override
  String toString() => 'InvoiceLine([PRIVATE])';
}

class InvoiceReturn {
  InvoiceReturn.fromJson(Object? raw) {
    final j = jsonObject(raw);
    id = documentId(j, 'id');
    number = requiredString(j, 'number');
    date = businessTimestamp(j, 'date');
    total = decimal(j, 'total');
    refund = decimal(j, 'refund');
    refundMethod = nullableText(j, 'refundMethod');
  }
  late final int id;
  late final String number, date, total, refund;
  late final String? refundMethod;
  @override
  String toString() => 'InvoiceReturn([PRIVATE])';
}

class InvoiceDetail {
  InvoiceDetail(this.invoice, this.items, this.returns);
  final SalesInvoice invoice;
  final InventoryPage<InvoiceLine> items;
  final InventoryPage<InvoiceReturn> returns;
  @override
  String toString() => 'InvoiceDetail([PRIVATE])';
}

class SalesReport {
  SalesReport(this.summary, this.trend, this.top);
  final SalesSummary summary;
  final List<SalesTrendPoint> trend;
  final List<TopProduct> top;
  @override
  String toString() => 'SalesReport([PRIVATE])';
}
