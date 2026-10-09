import '../../../core/errors/app_failure.dart';
import '../../../core/network/api_client.dart';
import '../../inventory/data/inventory_models.dart';
import '../../final_features/data/feature_access.dart';
import '../../final_features/data/feature_query.dart';
import '../../final_features/state/feature_state.dart';
import 'admin_models.dart';

class AdminRepository {
  AdminRepository(this.client);
  final ApiClient client;
  Future<AdminMetadata> metadata(FeatureAccess access) async {
    requireFeature(access.users);
    final raw = await Future.wait([
      client.send('GET', 'manager/admin/roles'),
      client.send('GET', 'manager/admin/permissions'),
    ]);
    if (raw.any((v) => v is! List)) throw AppFailure.malformed;
    final roles = List<AdminRole>.unmodifiable(
      (raw[0] as List).map(AdminRole.fromJson),
    );
    final permissions = List<AdminPermission>.unmodifiable(
      (raw[1] as List).map(AdminPermission.fromJson),
    );
    if (roles.map((r) => r.code).toSet().length != roles.length ||
        permissions.map((p) => p.code).toSet().length != permissions.length) {
      throw AppFailure.malformed;
    }
    return AdminMetadata(roles, permissions);
  }

  Future<FeaturePage<AdminUser>> list(
    FeatureAccess access,
    AdminQuery query,
    int page,
    int size, {
    required AdminMetadata metadata,
  }) async {
    requireFeature(access.users);
    if (query.role != null &&
        !metadata.roles.any((r) => r.code == query.role)) {
      throw AppFailure.malformed;
    }
    final q = featureSearch(query.search);
    final entries = InventoryPage<AdminUser>.fromJson(
      await client.send(
        'GET',
        featurePath('manager/admin/users', {
          ...featurePage(page, size),
          'sort': query.sort.api,
          if (q.isNotEmpty) 'q': q,
          if (query.role != null) 'role': query.role!,
          if (query.active != null) 'active': '${query.active}',
        }),
      ),
      AdminUser.fromJson,
      expectedPage: page,
      expectedSize: size,
    );
    if (entries.items.map((u) => u.id).toSet().length != entries.items.length) {
      throw AppFailure.malformed;
    }
    return FeaturePage(entries);
  }

  Future<AdminUser> detail(FeatureAccess access, int id) async {
    requireFeature(access.users);
    featureId(id);
    final u = AdminUser.fromJson(
      await client.send('GET', 'manager/admin/users/$id'),
    );
    if (u.id != id) throw AppFailure.malformed;
    return u;
  }

  Future<AdminUser> create(
    FeatureAccess access,
    AdminMetadata metadata,
    String username,
    AdminProfile profile,
    String password,
    String confirm,
  ) async {
    requireFeature(access.create);
    final normalized = username.trim().toLowerCase();
    if (!RegExp(r'^[a-z0-9._-]{3,50}$').hasMatch(normalized)) {
      throw const AppFailure(
        'USERNAME',
        'اسم المستخدم: 3–50 حرفاً إنجليزياً أو رقماً أو . _ -',
      );
    }
    adminPassword(password, confirm, normalized);
    final body = profile.body(metadata)..remove('active');
    body.addAll({
      'username': normalized,
      'password': password,
      'confirmPassword': confirm,
    });
    final created = AdminUser.fromJson(
      await client.send('POST', 'manager/admin/users', body: body),
    );
    if (created.username != normalized ||
        !created.active ||
        !created.mustChange) {
      throw AppFailure.malformed;
    }
    return created;
  }

  Future<AdminUser> edit(
    FeatureAccess access,
    AdminMetadata metadata,
    AdminUser target,
    AdminProfile profile,
  ) async {
    requireFeature(access.edit);
    featureId(target.id);
    if (target.id == access.user?.id &&
        (!profile.active || profile.roleCode != access.user?.roleCode)) {
      throw const AppFailure(
        'SELF_EDIT',
        'لا يمكنك تعطيل حسابك أو تغيير دورك بنفسك.',
      );
    }
    return _result(
      target.id,
      await client.send(
        'PUT',
        'manager/admin/users/${target.id}',
        body: profile.body(metadata),
      ),
    );
  }

  Future<AdminUser> active(
    FeatureAccess access,
    AdminUser target,
    bool active,
  ) async {
    requireFeature(access.edit);
    featureId(target.id);
    if (!active && target.id == access.user?.id) {
      throw const AppFailure('SELF_DISABLE', 'لا يمكنك تعطيل حسابك.');
    }
    return _result(
      target.id,
      await client.send(
        'POST',
        'manager/admin/users/${target.id}/${active ? 'enable' : 'disable'}',
      ),
    );
  }

  Future<AdminUser> reset(
    FeatureAccess access,
    AdminUser target,
    String password,
    String confirm,
  ) async {
    requireFeature(access.reset);
    featureId(target.id);
    if (target.id == access.user?.id) {
      throw const AppFailure(
        'SELF_RESET',
        'استخدم تغيير كلمة المرور من حسابك الشخصي.',
      );
    }
    adminPassword(password, confirm, target.username);
    final result = _result(
      target.id,
      await client.send(
        'POST',
        'manager/admin/users/${target.id}/reset-password',
        body: {'password': password, 'confirmPassword': confirm},
      ),
    );
    if (!result.mustChange) {
      throw AppFailure.malformed;
    }
    return result;
  }

  AdminUser _result(int id, Object? raw) {
    final u = AdminUser.fromJson(raw);
    if (u.id != id) throw AppFailure.malformed;
    return u;
  }
}
