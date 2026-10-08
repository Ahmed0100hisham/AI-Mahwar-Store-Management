import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:manager_app/core/config/app_config.dart';
import 'package:manager_app/core/errors/app_failure.dart';
import 'package:manager_app/core/network/api_transport.dart';
import 'package:manager_app/core/storage/token_vault.dart';

class FixtureAdapter implements HttpClientAdapter {
  late Future<ResponseBody> Function(RequestOptions options) handler;
  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) => handler(options);
  @override
  void close({bool force = false}) {}
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late FixtureAdapter adapter;
  late DioApiTransport transport;
  setUp(() {
    adapter = FixtureAdapter();
    final dio = Dio()..httpClientAdapter = adapter;
    transport = DioApiTransport(AppConfig('https://example.invalid'), dio: dio);
  });
  test('transport attaches bearer only to protected requests and disables caching/redirects', () async {
    var protected = false;
    adapter.handler = (options) async {
      expect(options.uri.toString(), 'https://example.invalid/api/v1/auth/me');
      expect(
        options.headers['Authorization'],
        protected ? 'Bearer fixture-token' : null,
      );
      expect(options.headers['Cache-Control'], 'no-store');
      expect(options.followRedirects, isFalse);
      expect(options.connectTimeout, const Duration(seconds: 10));
      expect(options.receiveTimeout, const Duration(seconds: 20));
      return ResponseBody.fromString(
        jsonEncode({'ok': true}),
        200,
        headers: {
          Headers.contentTypeHeader: ['application/json'],
        },
      );
    };
    await transport.send('GET', 'auth/me');
    protected = true;
    await transport.send('GET', 'auth/me', accessToken: 'fixture-token');
    protected = false;
    await transport.send('GET', 'auth/me');
  });
  test('malformed JSON cannot expose transport diagnostics', () async {
    adapter.handler = (_) async => ResponseBody.fromString(
      'not json fixture-password',
      200,
      headers: {
        Headers.contentTypeHeader: ['application/json'],
      },
    );
    await expectLater(
      transport.send('GET', 'auth/me'),
      throwsA(
        isA<AppFailure>().having((e) => e.code, 'code', 'INVALID_RESPONSE'),
      ),
    );
  });
  for (final type in [
    DioExceptionType.connectionTimeout,
    DioExceptionType.receiveTimeout,
    DioExceptionType.connectionError,
    DioExceptionType.badCertificate,
  ]) {
    test('network error $type is safe and localized', () async {
      adapter.handler = (options) async => throw DioException(
        requestOptions: options,
        type: type,
        message: 'fixture-secret',
      );
      await expectLater(
        transport.send('GET', 'auth/me'),
        throwsA(
          isA<AppFailure>().having(
            (e) => e.message.contains('fixture-secret'),
            'leaks',
            false,
          ),
        ),
      );
    });
  }
  test('unsafe route cannot send bearer to another host', () async {
    adapter.handler = (_) async => throw StateError('must not send');
    await expectLater(
      transport.send(
        'GET',
        'https://example.invalid',
        accessToken: 'fixture-token',
      ),
      throwsA(isA<AppFailure>()),
    );
  });
  test(
    'secure vault isolates API origins and serializes deletion after rotation',
    () async {
      FlutterSecureStorage.setMockInitialValues({});
      final first = SecureTokenVault('https://first.invalid/api/v1/'),
          second = SecureTokenVault('https://second.invalid/api/v1/');
      await first.writeRefresh('fixture-refresh');
      expect(await second.readRefresh(), isNull);
      final write = first.writeRefresh('fixture-rotated');
      final clear = first.clear();
      await Future.wait([write, clear]);
      expect(await first.readRefresh(), isNull);
    },
  );
}
