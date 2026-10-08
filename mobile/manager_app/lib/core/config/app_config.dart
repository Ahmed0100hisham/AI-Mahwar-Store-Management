import 'package:flutter/foundation.dart';

class AppConfig {
  AppConfig(
    String value, {
    bool allowHttp = false,
    bool release = !kDebugMode,
  }) {
    final uri = Uri.tryParse(value.trim());
    if (uri == null ||
        !uri.hasAuthority ||
        uri.host.isEmpty ||
        uri.userInfo.isNotEmpty ||
        uri.hasQuery ||
        uri.hasFragment ||
        (uri.path != '' &&
            uri.path != '/' &&
            uri.path != '/api/v1' &&
            uri.path != '/api/v1/')) {
      throw const FormatException(
        'يجب ضبط API_BASE_URL على عنوان الخدمة الصحيح.',
      );
    }
    final host = uri.host;
    final parts = host.split('.').map(int.tryParse).toList();
    final privateHost =
        host == 'localhost' ||
        host == '::1' ||
        (parts.length == 4 &&
            parts.every((n) => n != null && n >= 0 && n <= 255) &&
            (parts[0] == 127 ||
                parts[0] == 10 ||
                (parts[0] == 192 && parts[1] == 168) ||
                (parts[0] == 172 && parts[1]! >= 16 && parts[1]! <= 31)));
    if (uri.scheme != 'https' &&
        !(uri.scheme == 'http' && !release && allowHttp && privateHost)) {
      throw const FormatException(
        'يلزم عنوان HTTPS. الاتصال المحلي غير المشفر متاح للتطوير الصريح فقط.',
      );
    }
    baseUrl = uri.replace(path: '/api/v1/').toString();
  }
  late final String baseUrl;
  static AppConfig fromEnvironment() => AppConfig(
    const String.fromEnvironment('API_BASE_URL'),
    allowHttp: const bool.fromEnvironment('ALLOW_INSECURE_HTTP'),
  );
}
