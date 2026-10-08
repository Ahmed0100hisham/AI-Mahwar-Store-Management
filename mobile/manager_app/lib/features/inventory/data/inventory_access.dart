import '../../auth/data/auth_models.dart';

class InventoryAccess {
  InventoryAccess(this.user);
  final CurrentUser? user;
  bool has(String code) => user?.allows(code) ?? false;
  bool get products => has('PRODUCTS_VIEW') || has('PRODUCTS');
  bool get inventory =>
      has('INVENTORY') && has('REPORTS_VIEW') && has('REPORTS_INVENTORY');
  bool get enter => products || inventory;
  bool get inactive => has('PRODUCTS');
  bool get cost => has('PRODUCT_COST');
  // The released movement service also calls the protected core product lookup.
  bool get movements => inventory && products;
}
