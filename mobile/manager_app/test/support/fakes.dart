import 'package:manager_app/core/network/api_transport.dart';
import 'package:manager_app/core/storage/token_vault.dart';

typedef Handler = Future<Object?> Function(
  String method,
  String path,
  Object? body,
  String? token,
);

class FakeTransport implements ApiTransport {
  late Handler handler;
  final calls = <({String method, String path, Object? body, String? token})>[];
  @override
  Future<Object?> send(
    String method,
    String path, {
    Object? body,
    String? accessToken,
  }) async {
    calls.add((method: method, path: path, body: body, token: accessToken));
    return handler(method, path, body, accessToken);
  }
}

class MemoryVault implements TokenVault {
  String? value;
  int writes = 0, clears = 0;
  bool fail = false;
  @override
  Future<String?> readRefresh() async {
    if (fail) {
      throw StateError('storage unavailable');
    }
    return value;
  }

  @override
  Future<void> writeRefresh(String value) async {
    if (fail) {
      throw StateError('storage unavailable');
    }
    writes++;
    this.value = value;
  }

  @override
  Future<void> clear() async {
    if (fail) {
      throw StateError('storage unavailable');
    }
    clears++;
    value = null;
  }
}

Map<String, Object?> userJson({
  bool restricted = false,
  List<String> permissions = const ['DASHBOARD', 'SALES_VIEW'],
}) => {
  'id': 1,
  'username': 'fixture',
  'fullName': 'مستخدم الاختبار',
  'roleCode': 'CASHIER',
  'roleName': 'كاشير',
  'mustChangePassword': restricted,
  'permissions': permissions,
};
Map<String, Object?> loginJson({
  bool restricted = false,
  String access = 'fixture-access',
  String refresh = 'fixture-refresh',
}) => {
  'accessToken': access,
  'tokenType': 'Bearer',
  'expiresIn': 900,
  'expiresAt': '2099-01-01T00:00:00Z',
  'mustChangePassword': restricted,
  'user': userJson(restricted: restricted),
  'refreshToken': restricted ? null : refresh,
  'sid': restricted ? null : '00000000-0000-0000-0000-000000000001',
  'refreshExpiresAt': restricted ? null : '2099-01-02T00:00:00Z',
  'absoluteExpiresAt': restricted ? null : '2099-02-01T00:00:00Z',
};
Map<String, Object?> sessionJson({bool current = false}) => {
  'sid': '00000000-0000-0000-0000-000000000001',
  'createdAt': '2026-01-01T00:00:00Z',
  'lastActivityAt': '2026-01-02T00:00:00Z',
  'idleExpiresAt': '2099-01-01T00:00:00Z',
  'absoluteExpiresAt': '2099-02-01T00:00:00Z',
  'current': current,
  'deviceLabel': 'جهاز الاختبار',
  'status': 'ACTIVE',
};
