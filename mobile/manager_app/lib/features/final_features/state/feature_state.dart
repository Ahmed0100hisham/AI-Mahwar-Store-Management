import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../../core/errors/app_failure.dart';
import '../../auth/state/auth_controller.dart';
import '../../inventory/data/inventory_models.dart';
import '../data/feature_access.dart';

/// Read state scoped to the live account, session, role and permissions.
class FeatureState<T> extends ChangeNotifier {
  FeatureState(this.auth, {required this.permit, required this.request}) {
    _scope = scope;
    auth.addListener(_changed);
  }
  final AuthController auth;
  final bool Function(FeatureAccess) permit;
  final Future<T> Function() request;
  FeatureAccess get access => FeatureAccess(auth.user);
  bool get allowed => auth.status == AuthStatus.authenticated && permit(access);
  T? data;
  bool loading = false, disposed = false;
  AppFailure? error;
  int generation = 0;
  Future<void>? _pending;
  late String _scope;
  String get scope {
    final codes = auth.user?.permissions.toList() ?? <String>[];
    codes.sort();
    return '${auth.status}:${auth.sessionEpoch}:${auth.user?.id}:${auth.user?.roleCode}:${codes.join(',')}';
  }

  bool current(int value) =>
      !disposed && allowed && generation == value && _scope == scope;
  void clear() {
    data = null;
    error = null;
    loading = false;
    _pending = null;
  }

  void _changed() {
    if (disposed || _scope == scope) return;
    _scope = scope;
    invalidate();
    if (allowed) unawaited(load());
  }

  void invalidate() {
    generation++;
    clear();
    if (!disposed) notifyListeners();
  }

  AppFailure safe(Object e) => e is AppFailure ? e : AppFailure.malformed;
  Future<void> fail(AppFailure failure) async {
    if (failure.status == 401 ||
        failure.status == 403 ||
        failure.status == 404) {
      invalidate();
      error = failure;
      notifyListeners();
      if (failure.status != 404) await auth.checkSession();
    } else {
      error = failure;
    }
  }

  void accept(T result) {
    data = result;
  }

  Future<void> load() {
    if (disposed) return Future.value();
    if (_pending != null) return _pending!;
    if (!allowed) {
      invalidate();
      error = AppFailure.http(403, null);
      notifyListeners();
      return Future.value();
    }
    final value = ++generation;
    loading = true;
    error = null;
    notifyListeners();
    late final Future<void> pending;
    pending = _load(value).whenComplete(() {
      if (identical(_pending, pending)) _pending = null;
    });
    return _pending = pending;
  }

  Future<void> _load(int value) async {
    try {
      final result = await request();
      if (current(value)) accept(result);
    } catch (e) {
      if (current(value)) await fail(safe(e));
    } finally {
      if (current(value)) {
        loading = false;
        notifyListeners();
      }
    }
  }

  @override
  void dispose() {
    disposed = true;
    generation++;
    clear();
    auth.removeListener(_changed);
    super.dispose();
  }
}

class FeaturePage<T> {
  FeaturePage(this.entries, {this.header});
  final InventoryPage<T> entries;
  final Object? header;
}

class FeaturePaged<T> extends FeatureState<FeaturePage<T>> {
  FeaturePaged(
    super.auth, {
    required super.permit,
    required this.fetch,
    this.identity,
    this.size = 20,
  }) : super(request: () => fetch(0, size));
  final Future<FeaturePage<T>> Function(int page, int size) fetch;
  final Object Function(T)? identity;
  final int size;
  List<T> items = const [];
  int page = -1, total = 0, pages = 0;
  bool moreLoading = false, _lastNonempty = false, searching = false;
  AppFailure? moreError;
  Future<void>? _next;
  Timer? _debounce;
  bool get hasMore => _lastNonempty && page + 1 < pages && page < 10000;
  @override
  void clear() {
    super.clear();
    _debounce?.cancel();
    _debounce = null;
    items = const [];
    page = -1;
    total = pages = 0;
    moreLoading = _lastNonempty = searching = false;
    moreError = null;
    _next = null;
  }

  void queryChanged({bool debounce = false}) {
    if (disposed) return;
    invalidate();
    searching = debounce;
    notifyListeners();
    if (debounce) {
      _debounce = Timer(const Duration(milliseconds: 350), () {
        searching = false;
        unawaited(load());
      });
    } else {
      unawaited(load());
    }
  }

  @override
  void accept(FeaturePage<T> result) {
    super.accept(result);
    items = result.entries.items;
    page = result.entries.page;
    total = result.entries.total;
    pages = result.entries.pages;
    _lastNonempty = items.isNotEmpty;
  }

  @override
  Future<void> load() {
    _debounce?.cancel();
    searching = false;
    _next = null;
    moreLoading = false;
    moreError = null;
    return super.load();
  }

  Future<void> nextPage() {
    if (_next != null) return _next!;
    if (disposed ||
        !allowed ||
        !hasMore ||
        loading ||
        searching ||
        error != null) {
      return Future.value();
    }
    final value = generation, requested = page + 1;
    moreLoading = true;
    moreError = null;
    notifyListeners();
    late final Future<void> pending;
    pending = _append(value, requested).whenComplete(() {
      if (identical(_next, pending)) _next = null;
    });
    return _next = pending;
  }

  Future<void> _append(int value, int requested) async {
    try {
      final result = await fetch(requested, size);
      if (current(value)) {
        final entries = result.entries;
        final seen = identity == null ? null : items.map(identity!).toSet();
        items = List.unmodifiable([
          ...items,
          ...entries.items.where(
            (item) => seen == null || seen.add(identity!(item)),
          ),
        ]);
        page = entries.page;
        total = entries.total;
        pages = entries.pages;
        _lastNonempty = entries.items.isNotEmpty;
        // The last explicit first-page header is retained; no snapshot is promised.
      }
    } catch (e) {
      if (current(value)) {
        final f = safe(e);
        if (f.status == 401 || f.status == 403 || f.status == 404) {
          await fail(f);
        } else {
          moreError = f;
        }
      }
    } finally {
      if (current(value)) {
        moreLoading = false;
        notifyListeners();
      }
    }
  }
}
