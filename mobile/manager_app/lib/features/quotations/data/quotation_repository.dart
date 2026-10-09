import '../../../core/errors/app_failure.dart';
import '../../../core/network/api_client.dart';
import '../../auth/data/auth_models.dart';
import '../../inventory/data/inventory_models.dart';
import '../../final_features/data/feature_access.dart';
import '../../final_features/data/feature_query.dart';
import '../../final_features/state/feature_state.dart';
import 'quotation_models.dart';

class QuotationRepository {
  QuotationRepository(this.client);
  final ApiClient client;
  Future<FeaturePage<Quotation>> list(
    FeatureAccess access,
    QuotationQuery query,
    int page,
    int size,
  ) async {
    requireFeature(access.quotations);
    if (query.customerId != null) featureId(query.customerId!);
    final q = featureSearch(query.search);
    final raw = await client.send(
      'GET',
      featurePath('manager/quotations', {
        ...query.range.query,
        ...featurePage(page, size),
        'sort': query.sort.api,
        if (q.isNotEmpty) 'q': q,
        if (query.status != null) 'status': query.status!.api,
        if (query.customerId != null) 'customerId': '${query.customerId}',
        if (query.pastValidity != null) 'pastValidity': '${query.pastValidity}',
      }),
    );
    final entries = InventoryPage<Quotation>.fromJson(
      raw,
      (v) => Quotation.fromJson(v, access),
      expectedPage: page,
      expectedSize: size,
    );
    _unique(entries.items.map((q) => q.id));
    return FeaturePage(entries);
  }

  Future<FeaturePage<QuotationLine>> detail(
    FeatureAccess access,
    int id,
    int page,
    int size,
  ) async {
    requireFeature(access.quotations);
    featureId(id);
    final j = jsonObject(
      await client.send(
        'GET',
        featurePath('manager/quotations/$id', featurePage(page, size)),
      ),
    );
    final header = Quotation.fromJson(j['quotation'], access);
    if (header.id != id) throw AppFailure.malformed;
    final entries = InventoryPage<QuotationLine>.fromJson(
      j['items'],
      QuotationLine.fromJson,
      expectedPage: page,
      expectedSize: size,
    );
    _unique(entries.items.map((q) => q.id));
    return FeaturePage(entries, header: header);
  }

  void _unique(Iterable<int> ids) {
    if (ids.toSet().length != ids.length) throw AppFailure.malformed;
  }
}
