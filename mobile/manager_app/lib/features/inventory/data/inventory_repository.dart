import '../../../core/errors/app_failure.dart';
import '../../../core/network/api_client.dart';
import 'inventory_access.dart';
import 'inventory_models.dart';

class InventoryRepository {
  InventoryRepository(this.client);
  final ApiClient client;
  void _page(int page, int size) {
    if (page < 0 || page > 10000 || size < 1 || size > 100) {
      throw AppFailure.malformed;
    }
  }

  Future<InventoryPage<InventoryProduct>> products(
    InventoryAccess access, {
    InventoryMode mode = InventoryMode.all,
    String search = '',
    InventorySort sort = InventorySort.name,
    bool includeInactive = false,
    int page = 0,
    int size = 20,
  }) async {
    if (!(mode == InventoryMode.all ? access.products : access.inventory) ||
        (includeInactive && !access.inactive)) {
      throw AppFailure.http(403, null);
    }
    _page(page, size);
    if (search.length > 100) {
      throw const AppFailure('SEARCH_LENGTH', 'الحد الأقصى للبحث 100 حرف.');
    }
    final query = <String, String>{
      'page': '$page',
      'size': '$size',
      'sort': sort.api,
      if (search.trim().isNotEmpty) 'q': search.trim(),
      if (mode == InventoryMode.all) 'includeInactive': '$includeInactive',
    };
    final path = Uri(
      path: mode == InventoryMode.all
          ? 'products'
          : 'manager/inventory/low-stock',
      queryParameters: query,
    ).toString();
    final raw = await client.send('GET', path);
    final result = InventoryPage<InventoryProduct>.fromJson(
      raw,
      (item) => InventoryProduct.fromJson(
        item,
        access,
        low: mode == InventoryMode.low,
      ),
      expectedPage: page,
      expectedSize: size,
    );
    if (result.items.map((p) => p.id).toSet().length != result.items.length) {
      throw AppFailure.malformed;
    }
    return result;
  }

  Future<InventoryProduct> detail(InventoryAccess access, int id) async {
    if (!access.products) throw AppFailure.http(403, null);
    if (id <= 0) throw AppFailure.malformed;
    final result = InventoryProduct.fromJson(
      await client.send('GET', 'manager/products/$id'),
      access,
      detail: true,
    );
    if (result.id != id) throw AppFailure.malformed;
    return result;
  }

  Future<InventoryPage<InventoryMovement>> movements(
    InventoryAccess access,
    int id,
    MovementRange range, {
    int page = 0,
    int size = 20,
  }) async {
    if (!access.movements) throw AppFailure.http(403, null);
    if (id <= 0) throw AppFailure.malformed;
    _page(page, size);
    final path = Uri(
      path: 'manager/products/$id/movements',
      queryParameters: {
        ...range.query,
        'page': '$page',
        'size': '$size',
        'sort': 'date,desc',
      },
    ).toString();
    return InventoryPage<InventoryMovement>.fromJson(
      await client.send('GET', path),
      (item) => InventoryMovement.fromJson(item, access),
      expectedPage: page,
      expectedSize: size,
    );
  }
}
