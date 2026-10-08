import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../../core/errors/app_failure.dart';
import '../../../core/network/api_client.dart';
import '../../../core/storage/token_vault.dart';
import '../data/auth_models.dart';
import '../data/auth_repository.dart';

enum AuthStatus { restoring, signedOut, restricted, authenticated }

class AuthController extends ChangeNotifier implements SessionCredentials {
  AuthController(this.repository, this.vault) {
    repository.client = ApiClient(repository.transport, this);
  }
  final AuthRepository repository;
  final TokenVault vault;
  AuthStatus status = AuthStatus.restoring;
  CurrentUser? user;
  LoginTokens? _tokens;
  String? _refreshToken;
  AppFailure? error;
  bool busy = false;
  int _epoch = 0;
  Future<void>? _refreshing, _validating;
  @override
  String? get accessToken => _tokens?.accessToken;
  @override
  int get sessionEpoch => _epoch;
  String? get sid => _tokens?.sid;
  bool get canRestore => _refreshToken != null && accessToken == null;
  void clearError() {
    error = null;
    notifyListeners();
  }

  void _publishUser(CurrentUser value) {
    user = value;
    status = value.mustChangePassword
        ? AuthStatus.restricted
        : AuthStatus.authenticated;
  }

  Future<void> _accept(LoginTokens tokens, int epoch) async {
    if (epoch != _epoch) {
      throw AppFailure.signedOut;
    }
    try {
      if (tokens.refreshToken == null) {
        await vault.clear();
      } else {
        await vault.writeRefresh(tokens.refreshToken!);
      }
    } catch (_) {
      throw AppFailure.storage;
    }
    if (epoch != _epoch) {
      throw AppFailure.signedOut;
    }
    _tokens = tokens;
    _refreshToken = tokens.refreshToken;
    _publishUser(tokens.user);
    notifyListeners();
  }

  Future<void> restore() async {
    if (busy || accessToken != null) {
      return;
    }
    busy = true;
    status = AuthStatus.restoring;
    error = null;
    final epoch = ++_epoch;
    notifyListeners();
    try {
      _refreshToken = await vault.readRefresh();
      if (_refreshToken == null) {
        status = AuthStatus.signedOut;
        return;
      }
      await _rotate(epoch);
      await revalidate();
    } on AppFailure catch (failure) {
      error = failure;
    } catch (_) {
      error = AppFailure.storage;
    } finally {
      busy = false;
      if (status == AuthStatus.restoring) {
        status = AuthStatus.signedOut;
      }
      notifyListeners();
    }
  }

  Future<bool> login(String username, String password) async {
    if (busy) {
      return false;
    }
    busy = true;
    error = null;
    final epoch = ++_epoch;
    _refreshing = null;
    _validating = null;
    notifyListeners();
    try {
      await _accept(await repository.login(username.trim(), password), epoch);
      await revalidate();
      return true;
    } on AppFailure catch (failure) {
      await terminate();
      error ??= failure;
      return false;
    } catch (_) {
      await terminate();
      error ??= AppFailure.malformed;
      return false;
    } finally {
      busy = false;
      notifyListeners();
    }
  }

  @override
  Future<void> refreshAfterUnauthorized(String failedToken) {
    // A late 401 for the previous access token must reuse the already rotated token.
    if (accessToken != null && accessToken != failedToken) {
      return Future.value();
    }
    if (_refreshing != null) {
      return _refreshing!;
    }
    late final Future<void> pending;
    pending = _rotate(_epoch).whenComplete(() {
      if (identical(_refreshing, pending)) {
        _refreshing = null;
      }
    });
    return _refreshing = pending;
  }

  Future<void> _rotate(int epoch) async {
    final refresh = _refreshToken;
    if (refresh == null) {
      await terminate();
      throw AppFailure.signedOut;
    }
    try {
      await _accept(await repository.refresh(refresh), epoch);
    } on AppFailure catch (failure) {
      if (epoch == _epoch &&
          (failure.status == 401 ||
              failure.status == 403 ||
              failure.code == 'INVALID_RESPONSE' ||
              failure.code == 'SECURE_STORAGE')) {
        await terminate();
      }
      rethrow;
    }
  }

  Future<void> revalidate() {
    if (_validating != null) {
      return _validating!;
    }
    late final Future<void> pending;
    pending = _me().whenComplete(() {
      if (identical(_validating, pending)) {
        _validating = null;
      }
    });
    return _validating = pending;
  }

  Future<void> _me() async {
    if (accessToken == null) {
      return;
    }
    final epoch = _epoch;
    final current = await repository.me();
    if (epoch != _epoch) {
      return;
    }
    _publishUser(current);
    error = null;
    notifyListeners();
  }

  Future<void> checkSession() async {
    try {
      await revalidate();
    } on AppFailure catch (failure) {
      error = failure;
      notifyListeners();
    }
  }

  Future<void> logout({bool all = false}) async {
    if (busy) {
      return;
    }
    busy = true;
    error = null;
    notifyListeners();
    try {
      await repository.logout(all: all);
    } on AppFailure catch (failure) {
      error = failure;
    } finally {
      await terminate();
      busy = false;
      notifyListeners();
    }
  }

  Future<bool> changePassword(
    String current,
    String next,
    String confirm,
  ) async {
    if (busy) {
      return false;
    }
    busy = true;
    error = null;
    notifyListeners();
    try {
      await repository.changePassword(current, next, confirm);
      await terminate();
      return true;
    } on AppFailure catch (failure) {
      error = failure;
      return false;
    } finally {
      busy = false;
      notifyListeners();
    }
  }

  @override
  Future<void> terminate() async {
    ++_epoch;
    _refreshing = null;
    _validating = null;
    _tokens = null;
    _refreshToken = null;
    user = null;
    status = AuthStatus.signedOut;
    notifyListeners();
    try {
      await vault.clear();
    } catch (_) {
      error = AppFailure.storage;
    }
    notifyListeners();
  }
}
