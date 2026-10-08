import 'package:flutter_test/flutter_test.dart';
import 'package:manager_app/core/config/app_config.dart';
import 'package:manager_app/core/errors/app_failure.dart';
import 'package:manager_app/core/routing/manager_destination.dart';
import 'package:manager_app/core/utils/money.dart';
import 'package:manager_app/features/auth/data/auth_models.dart';

import 'support/fakes.dart';

void main() {
  test('HTTPS API origin and version prefix normalize without credentials', () {
    expect(
      AppConfig('https://example.invalid').baseUrl,
      'https://example.invalid/api/v1/',
    );
    expect(
      AppConfig('https://example.invalid/api/v1/').baseUrl,
      'https://example.invalid/api/v1/',
    );
  });
  for (final url in [
    '',
    'ftp://example.invalid',
    'https://user:password@example.invalid',
    'https://example.invalid/?secret=value',
    'https://example.invalid/other',
  ]) {
    test(
      'invalid or sensitive configuration fails: ${url.split(':').first}',
      () => expect(() => AppConfig(url), throwsFormatException),
    );
  }
  test('cleartext requires explicit debug opt-in and private host', () {
    expect(() => AppConfig('http://10.0.2.2:8080'), throwsFormatException);
    expect(
      AppConfig(
        'http://10.0.2.2:8080',
        allowHttp: true,
        release: false,
      ).baseUrl,
      'http://10.0.2.2:8080/api/v1/',
    );
    expect(
      () => AppConfig('http://10.0.2.2:8080', allowHttp: true, release: true),
      throwsFormatException,
    );
    expect(
      () =>
          AppConfig('http://example.invalid', allowHttp: true, release: false),
      throwsFormatException,
    );
  });
  test('login DTOs enforce restricted semantics and redact diagnostics', () {
    final tokens = LoginTokens.fromJson(loginJson());
    expect(tokens.toString(), isNot(contains('fixture-access')));
    expect(tokens.toString(), isNot(contains('fixture-refresh')));
    final invalid = loginJson(restricted: true)
      ..['refreshToken'] = 'unexpected';
    expect(() => LoginTokens.fromJson(invalid), throwsA(isA<AppFailure>()));
    expect(
      () => CurrentUser.fromJson({'permissions': 'ADMIN'}),
      throwsA(isA<AppFailure>()),
    );
  });
  test('navigation uses permissions, never a hard-coded admin role', () {
    final j = userJson(permissions: ['SALES_VIEW'])..['roleCode'] = 'ADMIN';
    final user = CurrentUser.fromJson(j);
    expect(
      ManagerDestination.future
          .where((d) => d.visibleTo(user))
          .map((d) => d.label),
      ['الفواتير'],
    );
  });
  test(
    'KWD preserves exact decimal strings including huge and negative values',
    () {
      expect(
        formatKwd('9007199254740993.125'),
        '9,007,199,254,740,993.125 د.ك',
      );
      expect(formatKwd('-0.001'), '-0.001 د.ك');
      expect(formatKwd('0.000'), '0.000 د.ك');
      expect(() => formatKwd('1.2'), throwsFormatException);
    },
  );
  for (final status in [400, 401, 403, 404, 409, 423, 429, 500, 503]) {
    test('safe Arabic error for HTTP $status ignores internal text', () {
      final error = AppFailure.http(status, {
        'message': 'SQL stack password fixture-token',
        'code': 'unknown-secret',
      });
      expect(error.message, isNot(contains('SQL')));
      expect(error.toString(), isNot(contains('fixture-token')));
      expect(error.status, status);
      expect(error.message, isNotEmpty);
    });
  }
}
