import '../../../core/network/api_client.dart';
import '../../../core/network/api_transport.dart';
import '../../../core/errors/app_failure.dart';
import 'auth_models.dart';

class AuthRepository {
  AuthRepository(this.transport);
  final ApiTransport transport;
  late final ApiClient client;
  Future<LoginTokens> login(String username, String password) async =>
      LoginTokens.fromJson(
        await transport.send(
          'POST',
          'auth/login',
          body: {
            'username': username,
            'password': password,
            'deviceLabel': 'Manager app',
          },
        ),
      );
  Future<LoginTokens> refresh(String refreshToken) async =>
      LoginTokens.fromJson(
        await transport.send(
          'POST',
          'auth/refresh',
          body: {'refreshToken': refreshToken},
        ),
      );
  Future<CurrentUser> me() async =>
      CurrentUser.fromJson(await client.send('GET', 'auth/me'));
  Future<void> logout({bool all = false}) async {
    await client.send('POST', all ? 'auth/logout-all' : 'auth/logout');
  }

  Future<void> changePassword(
    String current,
    String next,
    String confirmation,
  ) async {
    await client.send(
      'POST',
      'auth/change-password',
      body: {
        'currentPassword': current,
        'newPassword': next,
        'confirmPassword': confirmation,
      },
    );
  }

  Future<List<DeviceSession>> sessions() async {
    final result = await client.send('GET', 'auth/sessions');
    if (result is! List) {
      throw AppFailure.malformed;
    }
    return result.map(DeviceSession.fromJson).toList(growable: false);
  }

  Future<void> revoke(String sid) async {
    await client.send('DELETE', 'auth/sessions/${Uri.encodeComponent(sid)}');
  }
}
