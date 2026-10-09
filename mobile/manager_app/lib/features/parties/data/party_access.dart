import '../../auth/data/auth_models.dart';

enum PartyKind {
  customer(
    'customers',
    'العملاء',
    'العميل',
    'receivables',
    'ديون العملاء',
    'CUSTOMERS_VIEW',
    'CUSTOMER_BALANCE_VIEW',
  ),
  supplier(
    'suppliers',
    'الموردون',
    'المورد',
    'payables',
    'مستحقات الموردين',
    'SUPPLIERS_VIEW',
    'SUPPLIER_BALANCE_VIEW',
  );

  const PartyKind(
    this.path,
    this.title,
    this.singular,
    this.debtPath,
    this.debtTitle,
    this.identityCode,
    this.balanceCode,
  );
  final String path,
      title,
      singular,
      debtPath,
      debtTitle,
      identityCode,
      balanceCode;
  String get balanceNote => this == customer
      ? 'الرصيد الموجب مستحق للمتجر؛ الرصيد السالب رصيد دائن للعميل. أثر الحركة: مدين ناقص دائن.'
      : 'الرصيد الموجب مستحق للمورد؛ الرصيد السالب رصيد لصالح المتجر. أثر الحركة: دائن ناقص مدين.';
}

class PartyAccess {
  PartyAccess(this.user, this.kind);
  final CurrentUser? user;
  final PartyKind kind;
  bool get identity =>
      user != null &&
      !user!.mustChangePassword &&
      user!.allows(kind.identityCode);
  bool get financial => identity && user!.allows(kind.balanceCode);
}
