import '../../../core/errors/app_failure.dart';
import '../../../core/network/api_client.dart';
import 'party_access.dart';
import 'party_models.dart';

class PartyRepository {
  PartyRepository(this.client);
  final ApiClient client;
  void _page(int page, int size) {
    if (page < 0 || page > 10000 || size < 1 || size > 100) {
      throw AppFailure.malformed;
    }
  }

  void _id(int id) {
    if (id < 1 || id > 2147483647) throw AppFailure.malformed;
  }

  void _require(PartyAccess access, {bool financial = false}) {
    if (!access.identity || (financial && !access.financial)) {
      throw AppFailure.http(403, null);
    }
  }

  Future<PartyListing> list(
    PartyAccess access, {
    PartyMode mode = PartyMode.all,
    PartySort? sort,
    String search = '',
    int page = 0,
    int size = 20,
  }) async {
    _require(access, financial: mode == PartyMode.outstanding);
    _page(page, size);
    final order =
        sort ??
        (mode == PartyMode.outstanding
            ? PartySort.balanceDescending
            : PartySort.name);
    if (order.financial && !access.financial) throw AppFailure.http(403, null);
    final q = search.trim();
    if (q.length > 100) {
      throw const AppFailure('SEARCH_LENGTH', 'الحد الأقصى للبحث 100 حرف.');
    }
    final path =
        'manager/${access.kind.path}${mode == PartyMode.outstanding ? '/${access.kind.debtPath}' : ''}';
    final raw = await client.send(
      'GET',
      Uri(
        path: path,
        queryParameters: {
          'page': '$page',
          'size': '$size',
          'sort': order.api,
          if (q.isNotEmpty) 'q': q,
        },
      ).toString(),
    );
    return PartyListing.fromJson(
      raw,
      access,
      page: page,
      size: size,
      mode: mode,
    );
  }

  Future<PartyProfile> detail(PartyAccess access, int id) async {
    _require(access);
    _id(id);
    final profile = PartyProfile.fromJson(
      await client.send('GET', 'manager/${access.kind.path}/$id'),
      access,
    );
    if (profile.id != id) throw AppFailure.malformed;
    return profile;
  }

  Future<PartyAccount> account(
    PartyAccess access,
    int id,
    PartyRange range, {
    int page = 0,
    int size = 20,
  }) async {
    _require(access, financial: true);
    _id(id);
    _page(page, size);
    final raw = await client.send(
      'GET',
      Uri(
        path: 'manager/${access.kind.path}/$id/account',
        queryParameters: {
          ...range.query,
          'page': '$page',
          'size': '$size',
          'sort': 'date,asc',
        },
      ).toString(),
    );
    return PartyAccount.fromJson(
      raw,
      access,
      id: id,
      page: page,
      size: size,
      requested: range,
    );
  }
}
