import '../../../core/errors/app_failure.dart';
import '../../dashboard/data/dashboard_models.dart';
import '../../inventory/data/inventory_models.dart';

String featurePath(String path, Map<String, String> query) =>
    Uri(path: path, queryParameters: query).toString();
void requireFeature(bool allowed) {
  if (!allowed) throw AppFailure.http(403, null);
}

void featureId(int id) {
  if (id < 1 || id > 2147483647) throw AppFailure.malformed;
}

Map<String, String> featurePage(int page, int size) {
  if (page < 0 || page > 10000 || size < 1 || size > 100) {
    throw AppFailure.malformed;
  }
  return {'page': '$page', 'size': '$size'};
}

String featureSearch(String value) {
  final q = value.trim();
  if (q.length > 100) {
    throw const AppFailure('SEARCH_LENGTH', 'الحد الأقصى للبحث 100 حرف.');
  }
  return q;
}

void checkFeatureRange(MovementRange requested, BusinessRange returned) {
  MovementRange.custom(returned.from.iso, returned.to.iso);
  if (requested.period == null) {
    returned.requireMatch(
      BusinessRange(
        BusinessDay.parse(requested.from),
        BusinessDay.parse(requested.to),
      ),
    );
  }
}
