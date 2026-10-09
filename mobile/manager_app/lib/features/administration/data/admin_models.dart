import '../../../core/errors/app_failure.dart';
import '../../auth/data/auth_models.dart';
import '../../inventory/data/inventory_models.dart';
import '../../sales/data/sales_models.dart';

class AdminUser {
  AdminUser.fromJson(Object? raw) {
    final j = jsonObject(raw);
    id = documentId(j, 'id');
    username = requiredString(j, 'username');
    fullName = requiredString(j, 'fullName');
    roleCode = requiredString(j, 'roleCode');
    roleName = requiredString(j, 'roleName');
    phone = nullableText(j, 'phone');
    email = nullableText(j, 'email');
    active = flag(j, 'active');
    mustChange = flag(j, 'mustChangePassword');
    createdAt = businessTimestamp(j, 'createdAt');
    lastLoginAt = j['lastLoginAt'] == null
        ? null
        : businessTimestamp(j, 'lastLoginAt');
  }
  late final int id;
  late final String username, fullName, roleCode, roleName, createdAt;
  late final String? phone, email, lastLoginAt;
  late final bool active, mustChange;
  @override
  String toString() => 'AdminUser([PRIVATE])';
}

class AdminRole {
  AdminRole.fromJson(Object? raw) {
    final j = jsonObject(raw);
    code = requiredString(j, 'code');
    name = requiredString(j, 'name');
  }
  late final String code, name;
}

class AdminPermission {
  AdminPermission.fromJson(Object? raw) {
    final j = jsonObject(raw);
    code = requiredString(j, 'code');
    description = requiredString(j, 'description');
    group = requiredString(j, 'group');
    if (j['roles'] is! List || (j['roles'] as List).any((v) => v is! String)) {
      throw AppFailure.malformed;
    }
    roles = Set.unmodifiable((j['roles'] as List).cast<String>());
  }
  late final String code, description, group;
  late final Set<String> roles;
}

class AdminMetadata {
  AdminMetadata(this.roles, this.permissions);
  final List<AdminRole> roles;
  final List<AdminPermission> permissions;
}

enum AdminSort {
  name('name,asc', 'الاسم أ–ي'),
  nameDescending('name,desc', 'الاسم ي–أ'),
  username('username,asc', 'اسم المستخدم أ–ي'),
  usernameDescending('username,desc', 'اسم المستخدم ي–أ'),
  newest('created,desc', 'الأحدث إنشاءً'),
  oldest('created,asc', 'الأقدم إنشاءً');

  const AdminSort(this.api, this.label);
  final String api, label;
}

class AdminQuery {
  String search = '';
  String? role;
  bool? active;
  AdminSort sort = AdminSort.name;
}

class AdminProfile {
  AdminProfile({
    required this.fullName,
    this.phone,
    this.email,
    required this.roleCode,
    required this.active,
  });
  final String fullName, roleCode;
  final String? phone, email;
  final bool active;
  Map<String, Object?> body(AdminMetadata metadata) {
    if (fullName.trim().isEmpty ||
        fullName.trim().length > 100 ||
        (phone?.length ?? 0) > 100 ||
        (email?.length ?? 0) > 100 ||
        !metadata.roles.any((r) => r.code == roleCode)) {
      throw const AppFailure(
        'PROFILE',
        'تحقق من الاسم وبيانات الاتصال والدور المتاح.',
      );
    }
    return {
      'fullName': fullName.trim(),
      'phone': phone?.trim().isEmpty == true ? null : phone?.trim(),
      'email': email?.trim().isEmpty == true ? null : email?.trim(),
      'roleCode': roleCode,
      'active': active,
    };
  }

  @override
  String toString() => 'AdminProfile([PRIVATE])';
}

void adminPassword(String password, String confirm, String username) {
  if (password.length < 8 ||
      password.length > 128 ||
      password != confirm ||
      password.toLowerCase() == username.trim().toLowerCase() ||
      !RegExp(r'\p{L}', unicode: true).hasMatch(password) ||
      !RegExp(r'\p{Nd}', unicode: true).hasMatch(password)) {
    throw const AppFailure(
      'PASSWORD_POLICY',
      'كلمة المرور: 8–128 حرفاً، حروف وأرقام، مختلفة عن اسم المستخدم؛ التأكيد مطابق.',
    );
  }
}
