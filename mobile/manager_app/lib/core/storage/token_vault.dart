import 'package:flutter_secure_storage/flutter_secure_storage.dart';

abstract interface class TokenVault {
  Future<String?> readRefresh();
  Future<void> writeRefresh(String value);
  Future<void> clear();
}

class SecureTokenVault implements TokenVault {
  SecureTokenVault(String apiOrigin, {FlutterSecureStorage? storage})
    : _storage = storage ?? const FlutterSecureStorage(),
      _key = 'manager.refresh.v1.${Uri.encodeComponent(apiOrigin)}';
  final FlutterSecureStorage _storage;
  final String _key;
  // Serialize rotation and deletion so logout cannot be undone by a pending write.
  Future<void> _pending = Future<void>.value();
  Future<void> _queue(Future<void> Function() operation) {
    final next = _pending.then((_) => operation());
    _pending = next.catchError((Object _) {});
    return next;
  }

  @override
  Future<String?> readRefresh() async {
    await _pending;
    return _storage.read(key: _key);
  }

  @override
  Future<void> writeRefresh(String value) =>
      _queue(() => _storage.write(key: _key, value: value));
  @override
  Future<void> clear() => _queue(() => _storage.delete(key: _key));
}
