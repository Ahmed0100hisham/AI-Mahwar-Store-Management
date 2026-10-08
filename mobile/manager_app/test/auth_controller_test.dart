import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:manager_app/core/errors/app_failure.dart';
import 'package:manager_app/features/auth/data/auth_repository.dart';
import 'package:manager_app/features/auth/state/auth_controller.dart';

import 'support/fakes.dart';

void main() {
  late FakeTransport transport;
  late MemoryVault vault;
  late AuthController auth;
  setUp(() {
    transport = FakeTransport();
    vault = MemoryVault();
    auth = AuthController(AuthRepository(transport), vault);
    transport.handler = (method, path, body, token) async => switch (path) {
      'auth/login' || 'auth/refresh' => loginJson(),
      'auth/me' => userJson(),
      'auth/sessions' => [sessionJson(current: true)],
      _ => null,
    };
  });
  tearDown(() => auth.dispose());

  test('login validates against me and persists only opaque refresh', () async {
    expect(await auth.login(' fixture ', 'fixture-password'), isTrue);
    expect(auth.status, AuthStatus.authenticated);
    expect(auth.user!.permissions, contains('DASHBOARD'));
    expect(vault.value, 'fixture-refresh');
    expect(transport.calls.last.token, 'fixture-access');
    expect((transport.calls.first.body as Map)['username'], 'fixture');
  });
  test(
    'wrong credentials clears memory and storage with safe failure',
    () async {
      transport.handler = (_, _, _, _) async => throw AppFailure.http(401, {
        'code': 'INVALID_CREDENTIALS',
        'message': 'unsafe',
      });
      expect(await auth.login('fixture', 'wrong'), isFalse);
      expect(auth.status, AuthStatus.signedOut);
      expect(vault.value, isNull);
      expect(auth.error!.code, 'INVALID_CREDENTIALS');
      expect(auth.error!.message, isNot(contains('unsafe')));
    },
  );
  test('duplicate login submission is ignored', () async {
    final gate = Completer<void>();
    transport.handler = (_, path, _, _) async {
      if (path == 'auth/login') {
        await gate.future;
        return loginJson();
      }
      return userJson();
    };
    final pending = auth.login('fixture', 'fixture-password');
    expect(await auth.login('fixture', 'fixture-password'), isFalse);
    gate.complete();
    expect(await pending, isTrue, reason: auth.error?.code);
    expect(transport.calls.where((c) => c.path == 'auth/login'), hasLength(1));
  });
  test('restricted login has no stored refresh and blocks shell', () async {
    transport.handler = (_, path, _, _) async => path == 'auth/login'
        ? loginJson(restricted: true)
        : userJson(restricted: true);
    expect(await auth.login('fixture', 'fixture-password'), isTrue);
    expect(auth.status, AuthStatus.restricted);
    expect(vault.value, isNull);
  });
  test(
    'password change clears every local credential and requires new login',
    () async {
      await auth.login('fixture', 'fixture-password');
      expect(
        await auth.changePassword(
          'fixture-password',
          'fixture-new',
          'fixture-new',
        ),
        isTrue,
      );
      expect(auth.status, AuthStatus.signedOut);
      expect(auth.accessToken, isNull);
      expect(vault.value, isNull);
      expect(transport.calls.last.path, 'auth/change-password');
    },
  );
  for (final all in [false, true]) {
    test(
      'logout all=$all clears credentials even on connection failure',
      () async {
        await auth.login('fixture', 'fixture-password');
        transport.handler = (_, _, _, _) async =>
            throw const AppFailure('CONNECTION', 'تعذر الاتصال');
        await auth.logout(all: all);
        expect(auth.status, AuthStatus.signedOut);
        expect(vault.value, isNull);
        expect(
          transport.calls.last.path,
          all ? 'auth/logout-all' : 'auth/logout',
        );
      },
    );
  }
  test('protected calls attach access and rotate/retry once', () async {
    await auth.login('fixture', 'fixture-password');
    transport.handler = (_, path, body, token) async {
      if (path == 'auth/refresh') {
        expect((body as Map)['refreshToken'], 'fixture-refresh');
        return loginJson(access: 'new-access', refresh: 'new-refresh');
      }
      if (token == 'fixture-access') {
        throw AppFailure.signedOut;
      }
      return userJson();
    };
    await auth.repository.me();
    expect(vault.value, 'new-refresh');
    expect(auth.accessToken, 'new-access');
    expect(
      transport.calls.where((c) => c.path == 'auth/refresh'),
      hasLength(1),
    );
    expect(transport.calls.where((c) => c.path == 'auth/me'), hasLength(3));
  });
  test('simultaneous 401 responses share a single refresh rotation', () async {
    await auth.login('fixture', 'fixture-password');
    final refresh = Completer<Object?>();
    transport.handler = (_, path, _, token) async {
      if (path == 'auth/refresh') {
        return refresh.future;
      }
      if (token == 'fixture-access') {
        throw AppFailure.signedOut;
      }
      return userJson();
    };
    final calls = List.generate(12, (_) => auth.repository.me());
    await Future<void>.delayed(Duration.zero);
    expect(
      transport.calls.where((c) => c.path == 'auth/refresh'),
      hasLength(1),
    );
    refresh.complete(
      loginJson(access: 'rotated-access', refresh: 'rotated-refresh'),
    );
    await Future.wait(calls);
    expect(vault.value, 'rotated-refresh');
  });
  test(
    'late 401 on old token reuses rotation without refreshing again',
    () async {
      await auth.login('fixture', 'fixture-password');
      transport.handler = (_, _, _, _) async =>
          loginJson(access: 'rotated-access');
      await auth.refreshAfterUnauthorized('fixture-access');
      await auth.refreshAfterUnauthorized('fixture-access');
      expect(
        transport.calls.where((c) => c.path == 'auth/refresh'),
        hasLength(1),
      );
    },
  );
  test(
    'second unauthorized response ends session without refresh loop',
    () async {
      await auth.login('fixture', 'fixture-password');
      transport.handler = (_, path, _, _) async {
        if (path == 'auth/refresh') {
          return loginJson(access: 'rotated-access');
        }
        throw AppFailure.signedOut;
      };
      await expectLater(auth.repository.me(), throwsA(isA<AppFailure>()));
      expect(auth.status, AuthStatus.signedOut);
      expect(vault.value, isNull);
      expect(
        transport.calls.where((c) => c.path == 'auth/refresh'),
        hasLength(1),
      );
    },
  );
  test('revoked refresh is terminal and wipes tokens', () async {
    await auth.login('fixture', 'fixture-password');
    transport.handler = (_, _, _, _) async => throw AppFailure.signedOut;
    await expectLater(auth.repository.me(), throwsA(isA<AppFailure>()));
    expect(auth.accessToken, isNull);
    expect(vault.value, isNull);
    expect(auth.status, AuthStatus.signedOut);
  });
  test('transient refresh failure keeps secure credential for retry', () async {
    await auth.login('fixture', 'fixture-password');
    transport.handler = (_, path, _, _) async {
      if (path == 'auth/refresh') {
        throw const AppFailure('TIMEOUT', 'timeout');
      }
      throw AppFailure.signedOut;
    };
    await expectLater(auth.repository.me(), throwsA(isA<AppFailure>()));
    expect(vault.value, 'fixture-refresh');
  });
  test('refresh response cannot resurrect logout', () async {
    await auth.login('fixture', 'fixture-password');
    final refresh = Completer<Object?>();
    transport.handler = (_, _, _, _) async => refresh.future;
    final pending = auth.refreshAfterUnauthorized('fixture-access');
    final checked = expectLater(pending, throwsA(isA<AppFailure>()));
    await auth.terminate();
    refresh.complete(loginJson(access: 'stale-access'));
    await checked;
    expect(auth.status, AuthStatus.signedOut);
    expect(vault.value, isNull);
  });
  test('late successful request from another account is discarded', () async {
    await auth.login('fixture', 'fixture-password');
    final response = Completer<Object?>();
    transport.handler = (_, _, _, _) async => response.future;
    final request = auth.repository.me();
    final checked = expectLater(request, throwsA(isA<AppFailure>()));
    await auth.terminate();
    response.complete(userJson());
    await checked;
  });
  test('permissions update from live me without relying on role', () async {
    await auth.login('fixture', 'fixture-password');
    transport.handler = (_, _, _, _) async =>
        userJson(permissions: ['QUOTATIONS_VIEW']);
    await auth.revalidate();
    expect(auth.user!.allows('DASHBOARD'), isFalse);
    expect(auth.user!.allows('QUOTATIONS_VIEW'), isTrue);
  });
  test('server 403 is preserved and never retried as refresh', () async {
    await auth.login('fixture', 'fixture-password');
    transport.handler = (_, _, _, _) async => throw AppFailure.http(403, null);
    await expectLater(
      auth.repository.sessions(),
      throwsA(isA<AppFailure>().having((e) => e.status, 'status', 403)),
    );
    expect(transport.calls.where((c) => c.path == 'auth/refresh'), isEmpty);
  });
  test('session DTO and revoke route match frozen contract', () async {
    await auth.login('fixture', 'fixture-password');
    final sessions = await auth.repository.sessions();
    expect(sessions.single.current, isTrue);
    await auth.repository.revoke(sessions.single.sid);
    expect(transport.calls.last.method, 'DELETE');
    expect(transport.calls.last.path, 'auth/sessions/${sessions.single.sid}');
  });
  test('startup restores only through rotated refresh and live me', () async {
    vault.value = 'saved-refresh';
    await auth.restore();
    expect(auth.status, AuthStatus.authenticated);
    expect(transport.calls.first.path, 'auth/refresh');
    expect(transport.calls.last.path, 'auth/me');
  });
  test('expired startup refresh returns to login and erases storage', () async {
    vault.value = 'expired-refresh';
    transport.handler = (_, _, _, _) async => throw AppFailure.signedOut;
    await auth.restore();
    expect(auth.status, AuthStatus.signedOut);
    expect(vault.value, isNull);
  });
  test('old refresh completion cannot replace a new account or clear its in-flight refresh', () async {
    await auth.login('fixture', 'fixture-password');
    final oldRefresh = Completer<Object?>(), newRefresh = Completer<Object?>();
    transport.handler = (_, _, _, _) async => oldRefresh.future;
    final oldPending = auth.refreshAfterUnauthorized('fixture-access');
    final oldChecked = expectLater(oldPending, throwsA(isA<AppFailure>()));
    await auth.terminate();
    transport.handler = (_, path, _, _) async {
      if (path == 'auth/login') {
        return loginJson(
          access: 'new-account-access',
          refresh: 'new-account-refresh',
        );
      }
      if (path == 'auth/refresh') {
        return newRefresh.future;
      }
      return userJson();
    };
    expect(await auth.login('fixture', 'fixture-password'), isTrue);
    final newPending = auth.refreshAfterUnauthorized('new-account-access');
    oldRefresh.complete(loginJson(access: 'obsolete-access'));
    await oldChecked;
    final coalesced = auth.refreshAfterUnauthorized('new-account-access');
    expect(
      transport.calls.where((c) => c.path == 'auth/refresh'),
      hasLength(2),
    );
    newRefresh.complete(
      loginJson(access: 'new-rotated-access', refresh: 'new-rotated-refresh'),
    );
    await Future.wait([newPending, coalesced]);
    expect(auth.accessToken, 'new-rotated-access');
    expect(vault.value, 'new-rotated-refresh');
  });
  test(
    'malformed rotation fails closed and erases stale credentials',
    () async {
      await auth.login('fixture', 'fixture-password');
      transport.handler = (_, _, _, _) async => {'unexpected': true};
      await expectLater(
        auth.refreshAfterUnauthorized('fixture-access'),
        throwsA(isA<AppFailure>()),
      );
      expect(auth.status, AuthStatus.signedOut);
      expect(vault.value, isNull);
    },
  );
  test(
    'storage failure fails closed instead of plain preferences fallback',
    () async {
      vault.fail = true;
      expect(await auth.login('fixture', 'fixture-password'), isFalse);
      expect(auth.accessToken, isNull);
      expect(auth.error!.code, 'SECURE_STORAGE');
    },
  );
}
