// Explicit opt-in: flutter test test/live_api_contract.dart --no-pub
// Run only against an externally provisioned disposable API fixture.
// Credentials arrive via the process environment and are never printed.
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:manager_app/core/config/app_config.dart';
import 'package:manager_app/core/errors/app_failure.dart';
import 'package:manager_app/core/network/api_transport.dart';
import 'package:manager_app/features/auth/data/auth_repository.dart';
import 'package:manager_app/features/auth/state/auth_controller.dart';

import 'support/fakes.dart';

void main() {
  final env = Platform.environment;
  final url = env['MANAGER_TEST_API_URL'];
  final username = env['MANAGER_TEST_USERNAME'];
  final password = env['MANAGER_TEST_PASSWORD'];
  final restrictedUsername = env['MANAGER_TEST_RESTRICTED_USERNAME'];
  final nextPassword = env['MANAGER_TEST_NEW_PASSWORD'];
  if (url == null ||
      username == null ||
      password == null ||
      restrictedUsername == null ||
      nextPassword == null) {
    throw StateError('Disposable API fixture environment is required.');
  }
  AuthController controller(MemoryVault vault) => AuthController(
    AuthRepository(
      DioApiTransport(AppConfig(url, allowHttp: true, release: false)),
    ),
    vault,
  );

  test('live backend login, me, rotation, own sessions, revoke, logout and logout-all', () async {
    final vault = MemoryVault();
    var auth = controller(vault);
    final second = controller(MemoryVault()), third = controller(MemoryVault());
    try {
      expect(
        await auth.login(username, password),
        isTrue,
        reason: auth.error?.code,
      );
      expect(
        await second.login(username, password),
        isTrue,
        reason: second.error?.code,
      );
      expect(auth.user!.username == username, isTrue);
      final before = vault.value;
      await auth.refreshAfterUnauthorized(auth.accessToken!);
      expect(
        vault.value != before,
        isTrue,
        reason: 'Refresh credential must rotate',
      );
      await auth.revalidate();
      auth.dispose();
      auth = controller(vault);
      await auth.restore();
      expect(auth.status, AuthStatus.authenticated, reason: auth.error?.code);
      final sessions = await auth.repository.sessions();
      expect(sessions.any((s) => s.current && s.sid == auth.sid), isTrue);
      expect(sessions.any((s) => s.sid == second.sid), isTrue);
      await auth.repository.revoke(second.sid!);
      await expectLater(second.repository.me(), throwsA(isA<AppFailure>()));
      expect(second.status, AuthStatus.signedOut);
      expect(
        await third.login(username, password),
        isTrue,
        reason: third.error?.code,
      );
      await auth.logout(all: true);
      expect(vault.value == null, isTrue);
      await expectLater(third.repository.me(), throwsA(isA<AppFailure>()));
      expect(third.status, AuthStatus.signedOut);
      expect(
        await auth.login(username, password),
        isTrue,
        reason: auth.error?.code,
      );
      await auth.logout();
      expect(auth.status, AuthStatus.signedOut);
    } finally {
      auth.dispose();
      second.dispose();
      third.dispose();
    }
  }, timeout: const Timeout(Duration(minutes: 2)));

  test('live restricted session changes password and requires fresh authentication', () async {
    final vault = MemoryVault();
    final auth = controller(vault);
    try {
      expect(
        await auth.login(restrictedUsername, password),
        isTrue,
        reason: auth.error?.code,
      );
      expect(auth.status, AuthStatus.restricted);
      expect(vault.value == null, isTrue);
      expect(
        await auth.changePassword(password, nextPassword, nextPassword),
        isTrue,
        reason: auth.error?.code,
      );
      expect(auth.status, AuthStatus.signedOut);
      expect(
        await auth.login(restrictedUsername, nextPassword),
        isTrue,
        reason: auth.error?.code,
      );
      expect(auth.status, AuthStatus.authenticated);
      await auth.logout();
    } finally {
      auth.dispose();
    }
  }, timeout: const Timeout(Duration(minutes: 2)));
}
