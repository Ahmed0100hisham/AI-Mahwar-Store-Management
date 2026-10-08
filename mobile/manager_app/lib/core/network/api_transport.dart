import 'package:dio/dio.dart';

import '../config/app_config.dart';
import '../errors/app_failure.dart';

abstract interface class ApiTransport {
  Future<Object?> send(
    String method,
    String path, {
    Object? body,
    String? accessToken,
  });
}

class DioApiTransport implements ApiTransport {
  DioApiTransport(AppConfig config, {Dio? dio}) : _dio = dio ?? Dio() {
    _dio.options = BaseOptions(
      baseUrl: config.baseUrl,
      connectTimeout: const Duration(seconds: 10),
      sendTimeout: const Duration(seconds: 15),
      receiveTimeout: const Duration(seconds: 20),
      followRedirects: false,
      contentType: Headers.jsonContentType,
      responseType: ResponseType.json,
      validateStatus: (_) => true,
      headers: {'Accept': 'application/json', 'Cache-Control': 'no-store'},
    );
    // No logging/cache interceptor. Requests and responses can contain credentials.
  }
  final Dio _dio;
  @override
  Future<Object?> send(
    String method,
    String path, {
    Object? body,
    String? accessToken,
  }) async {
    if (path.startsWith('/') || path.contains('://') || path.contains('..')) {
      throw const AppFailure('INVALID_PATH', 'تعذر تنفيذ الطلب.');
    }
    try {
      final response = await _dio.request<Object?>(
        path,
        data: body,
        options: Options(
          method: method,
          headers: {
            if (accessToken != null) 'Authorization': 'Bearer $accessToken',
          },
        ),
      );
      final status = response.statusCode ?? 0;
      if (status < 200 || status >= 300) {
        throw AppFailure.http(status, response.data);
      }
      return response.data;
    } on DioException catch (error) {
      switch (error.type) {
        case DioExceptionType.connectionTimeout:
        case DioExceptionType.sendTimeout:
        case DioExceptionType.receiveTimeout:
          throw const AppFailure(
            'TIMEOUT',
            'استغرق الاتصال وقتاً طويلاً. تحقق من الشبكة وأعد المحاولة.',
          );
        case DioExceptionType.connectionError:
          throw const AppFailure(
            'CONNECTION',
            'تعذر الاتصال بالخدمة. تحقق من اتصال الإنترنت.',
          );
        case DioExceptionType.badCertificate:
          throw const AppFailure('TLS', 'تعذر التحقق من أمان الاتصال بالخدمة.');
        default:
          throw AppFailure.malformed;
      }
    } on FormatException {
      throw AppFailure.malformed;
    }
  }
}
