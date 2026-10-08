import '../../../core/errors/app_failure.dart';

Map<String, dynamic> jsonObject(Object? value) {
  if (value is! Map<String, dynamic>) {
    throw AppFailure.malformed;
  }
  return value;
}

String requiredString(Map<String, dynamic> json, String key) {
  final value = json[key];
  if (value is! String || value.isEmpty) {
    throw AppFailure.malformed;
  }
  return value;
}

DateTime requiredDate(Map<String, dynamic> json, String key) {
  final value = DateTime.tryParse(requiredString(json, key));
  if (value == null) {
    throw AppFailure.malformed;
  }
  return value;
}

class CurrentUser {
  CurrentUser({
    required this.id,
    required this.username,
    required this.fullName,
    required this.roleCode,
    required this.roleName,
    required this.mustChangePassword,
    required Set<String> permissions,
  }) : permissions = Set.unmodifiable(permissions);
  factory CurrentUser.fromJson(Object? value) {
    final j = jsonObject(value);
    if (j['id'] is! int ||
        j['mustChangePassword'] is! bool ||
        j['permissions'] is! List ||
        (j['permissions'] as List).any((p) => p is! String)) {
      throw AppFailure.malformed;
    }
    return CurrentUser(
      id: j['id'] as int,
      username: requiredString(j, 'username'),
      fullName: requiredString(j, 'fullName'),
      roleCode: requiredString(j, 'roleCode'),
      roleName: requiredString(j, 'roleName'),
      mustChangePassword: j['mustChangePassword'] as bool,
      permissions: (j['permissions'] as List).cast<String>().toSet(),
    );
  }
  final int id;
  final String username, fullName, roleCode, roleName;
  final bool mustChangePassword;
  final Set<String> permissions;
  bool allows(String permission) => permissions.contains(permission);
}

class LoginTokens {
  LoginTokens({
    required this.accessToken,
    required this.user,
    required this.expiresAt,
    required this.mustChangePassword,
    this.refreshToken,
    this.sid,
  });
  factory LoginTokens.fromJson(Object? value) {
    final j = jsonObject(value);
    final user = CurrentUser.fromJson(j['user']);
    final restricted = j['mustChangePassword'];
    if (restricted is! bool ||
        restricted != user.mustChangePassword ||
        j['tokenType'] != 'Bearer' ||
        j['expiresIn'] is! num ||
        j['refreshToken'] is! String? ||
        j['sid'] is! String?) {
      throw AppFailure.malformed;
    }
    final refresh = j['refreshToken'] as String?;
    if ((!restricted &&
            (refresh == null || refresh.isEmpty || j['sid'] == null)) ||
        (restricted && refresh != null)) {
      throw AppFailure.malformed;
    }
    return LoginTokens(
      accessToken: requiredString(j, 'accessToken'),
      user: user,
      expiresAt: requiredDate(j, 'expiresAt'),
      mustChangePassword: restricted,
      refreshToken: refresh,
      sid: j['sid'] as String?,
    );
  }
  final String accessToken;
  final String? refreshToken, sid;
  final DateTime expiresAt;
  final bool mustChangePassword;
  final CurrentUser user;
  @override
  String toString() => 'LoginTokens([REDACTED])';
}

class DeviceSession {
  DeviceSession.fromJson(Object? value) {
    final j = jsonObject(value);
    sid = requiredString(j, 'sid');
    createdAt = requiredDate(j, 'createdAt');
    lastActivityAt = requiredDate(j, 'lastActivityAt');
    idleExpiresAt = requiredDate(j, 'idleExpiresAt');
    absoluteExpiresAt = requiredDate(j, 'absoluteExpiresAt');
    status = requiredString(j, 'status');
    if (j['current'] is! bool || j['deviceLabel'] is! String?) {
      throw AppFailure.malformed;
    }
    current = j['current'] as bool;
    deviceLabel = j['deviceLabel'] as String?;
  }
  late final String sid, status;
  late final String? deviceLabel;
  late final DateTime createdAt,
      lastActivityAt,
      idleExpiresAt,
      absoluteExpiresAt;
  late final bool current;
}
