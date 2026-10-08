import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../../core/errors/app_failure.dart';
import '../../auth/state/auth_controller.dart';
import '../data/dashboard_access.dart';
import '../data/dashboard_models.dart';
import '../data/dashboard_repository.dart';

class DashboardController extends ChangeNotifier {
  DashboardController(this.auth, this.repository) {
    _scope = _currentScope;
    auth.addListener(_authChanged);
  }
  final AuthController auth;
  final DashboardRepository repository;
  DashboardPeriod period = DashboardPeriod.today;
  DashboardData? data;
  AppFailure? error;
  bool loading = false, _disposed = false;
  int _generation = 0;
  Future<void>? _pending;
  late String _scope;
  String get _currentScope {
    final codes = auth.user?.permissions.toList() ?? <String>[];
    codes.sort();
    return '${auth.status}:${auth.sessionEpoch}:${auth.user?.id}:${codes.join(',')}';
  }

  bool get allowed =>
      auth.status == AuthStatus.authenticated &&
      auth.user != null &&
      DashboardAccess(auth.user!).dashboard;
  void _authChanged() {
    if (_scope == _currentScope) return;
    _scope = _currentScope;
    _generation++;
    _pending = null;
    data = null;
    error = null;
    loading = false;
    notifyListeners();
    if (allowed) unawaited(load());
  }

  Future<void> selectPeriod(DashboardPeriod next) {
    if (period == next) return load();
    period = next;
    _generation++;
    _pending = null;
    data = null;
    error = null;
    return load();
  }

  Future<void> load() {
    if (_disposed) return Future.value();
    if (_pending != null) return _pending!;
    if (!allowed) {
      data = null;
      error = AppFailure.http(403, null);
      loading = false;
      notifyListeners();
      return Future.value();
    }
    final generation = ++_generation;
    final scope = _scope;
    final access = DashboardAccess(auth.user!);
    if (!access.periods) period = DashboardPeriod.today;
    loading = true;
    error = null;
    notifyListeners();
    late final Future<void> request;
    request = _fetch(access, generation, scope).whenComplete(() {
      if (identical(_pending, request)) _pending = null;
    });
    return _pending = request;
  }

  bool _current(int generation, String scope) =>
      !_disposed &&
      generation == _generation &&
      scope == _currentScope &&
      allowed;
  Future<void> _fetch(
    DashboardAccess access,
    int generation,
    String scope,
  ) async {
    try {
      final result = await repository.load(access, period);
      if (_current(generation, scope)) data = result;
    } catch (failure) {
      if (_current(generation, scope)) {
        error = failure is AppFailure ? failure : AppFailure.malformed;
        if (error!.status == 403 || error!.status == 401) {
          data = null; // Never retain financial values after an authorization rejection.
          notifyListeners();
          await auth.checkSession();
        }
      }
    } finally {
      if (_current(generation, scope)) {
        loading = false;
        notifyListeners();
      }
    }
  }

  @override
  void dispose() {
    _disposed = true;
    _generation++;
    auth.removeListener(_authChanged);
    super.dispose();
  }
}
