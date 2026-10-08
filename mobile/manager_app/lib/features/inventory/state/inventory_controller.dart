import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../../core/errors/app_failure.dart';
import '../../auth/state/auth_controller.dart';
import '../data/inventory_access.dart';
import '../data/inventory_models.dart';
import '../data/inventory_repository.dart';

abstract class InventoryScope extends ChangeNotifier {
  InventoryScope(this.auth, this.repository) {
    _scope = _currentScope;
    auth.addListener(_authChanged);
  }
  final AuthController auth;
  final InventoryRepository repository;
  int generation = 0;
  bool disposed = false;
  late String _scope;
  InventoryAccess get access => InventoryAccess(auth.user);
  bool get permitted;
  bool get allowed => auth.status == AuthStatus.authenticated && permitted;
  String get _currentScope {
    final permissions = auth.user?.permissions.toList() ?? <String>[];
    permissions.sort();
    return '${auth.status}:${auth.sessionEpoch}:${auth.user?.id}:${permissions.join(',')}';
  }

  bool current(int value) =>
      !disposed && value == generation && _scope == _currentScope && allowed;
  void clear();
  void adjustAccess() {}
  Future<void> load();
  void _authChanged() {
    if (disposed || _scope == _currentScope) return;
    _scope = _currentScope;
    generation++;
    clear();
    adjustAccess();
    notifyListeners();
    if (allowed) unawaited(load());
  }

  AppFailure safe(Object failure) =>
      failure is AppFailure ? failure : AppFailure.malformed;
  bool forbidden(AppFailure error) =>
      error.status == 401 || error.status == 403;
  @override
  void dispose() {
    disposed = true;
    generation++;
    clear();
    auth.removeListener(_authChanged);
    super.dispose();
  }
}

abstract class InventoryPaged<T> extends InventoryScope {
  InventoryPaged(super.auth, super.repository, {this.size = 20});
  final int size;
  List<T> items = const [];
  int page = -1, total = 0, pages = 0;
  bool loaded = false,
      loading = false,
      loadingMore = false,
      searching = false,
      _lastNonempty = false;
  String loadedContext = '';
  AppFailure? error, nextError;
  Future<void>? _first, _next;
  Timer? debounce;
  String get contextLabel;
  Future<InventoryPage<T>> request(int page);
  List<T> merge(List<T> previous, List<T> next) => [...previous, ...next];
  bool get hasMore =>
      loaded && _lastNonempty && page + 1 < pages && page < 10000;
  bool get canLoadMore => hasMore && !loading && !searching && error == null;
  @override
  void clear() {
    debounce?.cancel();
    debounce = null;
    _first = null;
    _next = null;
    items = const [];
    page = -1;
    total = 0;
    pages = 0;
    loaded = false;
    loading = false;
    loadingMore = false;
    searching = false;
    _lastNonempty = false;
    error = null;
    nextError = null;
    loadedContext = '';
  }

  void queryChanged({bool delay = false}) {
    if (disposed) return;
    debounce?.cancel();
    generation++;
    _first = null;
    _next = null;
    loading = false;
    loadingMore = false;
    searching = delay;
    error = null;
    nextError = null;
    notifyListeners();
    if (delay) {
      debounce = Timer(const Duration(milliseconds: 350), () {
        searching = false;
        unawaited(load());
      });
    } else {
      unawaited(load());
    }
  }

  @override
  Future<void> load() {
    if (disposed) return Future.value();
    debounce?.cancel();
    searching = false;
    if (_first != null) return _first!;
    if (!allowed) {
      clear();
      error = AppFailure.http(403, null);
      notifyListeners();
      return Future.value();
    }
    final value = ++generation, label = contextLabel;
    _next = null;
    loadingMore = false;
    loading = true;
    error = null;
    nextError = null;
    notifyListeners();
    late final Future<void> pending;
    pending = _fetch(value, label, false).whenComplete(() {
      if (identical(_first, pending)) _first = null;
    });
    return _first = pending;
  }

  Future<void> nextPage() {
    if (_next != null) return _next!;
    if (disposed || !allowed || !canLoadMore) return Future.value();
    final value = generation;
    loadingMore = true;
    nextError = null;
    notifyListeners();
    late final Future<void> pending;
    pending = _fetch(value, loadedContext, true).whenComplete(() {
      if (identical(_next, pending)) _next = null;
    });
    return _next = pending;
  }

  Future<void> _fetch(int value, String label, bool append) async {
    try {
      final result = await request(append ? page + 1 : 0);
      if (current(value)) {
        items = List.unmodifiable(
          append ? merge(items, result.items) : result.items,
        );
        page = result.page;
        total = result.total;
        pages = result.pages;
        _lastNonempty = result.items.isNotEmpty;
        loaded = true;
        loadedContext = label;
      }
    } catch (failure) {
      if (current(value)) {
        final mapped = safe(failure);
        if (forbidden(mapped)) {
          clear();
          error = mapped;
          notifyListeners();
          await auth.checkSession();
        } else if (append) {
          nextError = mapped;
        } else {
          error = mapped;
        }
      }
    } finally {
      if (current(value)) {
        loading = false;
        loadingMore = false;
        notifyListeners();
      }
    }
  }
}

class InventoryListController extends InventoryPaged<InventoryProduct> {
  InventoryListController(
    super.auth,
    super.repository, {
    super.size,
    bool lowStock = false,
  }) {
    mode = lowStock || !access.products ? InventoryMode.low : InventoryMode.all;
    sort = mode == InventoryMode.low
        ? InventorySort.quantity
        : InventorySort.name;
  }
  late InventoryMode mode;
  late InventorySort sort;
  String search = '';
  bool includeInactive = false;
  @override
  bool get permitted =>
      mode == InventoryMode.all ? access.products : access.inventory;
  @override
  String get contextLabel =>
      '${mode == InventoryMode.all ? 'المنتجات' : 'المخزون المنخفض'}${search.isEmpty ? '' : ' • $search'} • ${sort.label}${includeInactive ? ' • يشمل غير النشط' : ''}';
  @override
  void adjustAccess() {
    if (!access.inactive) includeInactive = false;
    if (!permitted && access.enter) {
      mode = access.products ? InventoryMode.all : InventoryMode.low;
    }
  }

  void setSearch(String text, {bool immediate = false}) {
    final next = text.trim();
    if (next == search && !immediate) return;
    search = next;
    queryChanged(delay: !immediate && next.isNotEmpty);
  }

  void setMode(InventoryMode next) {
    if (mode == next ||
        !(next == InventoryMode.all ? access.products : access.inventory)) {
      return;
    }
    mode = next;
    includeInactive = false;
    queryChanged();
  }

  void setSort(InventorySort next) {
    if (sort != next) {
      sort = next;
      queryChanged();
    }
  }

  void setInactive(bool value) {
    if (access.inactive &&
        mode == InventoryMode.all &&
        includeInactive != value) {
      includeInactive = value;
      queryChanged();
    }
  }

  @override
  Future<InventoryPage<InventoryProduct>> request(int page) =>
      repository.products(
        access,
        mode: mode,
        search: search,
        sort: sort,
        includeInactive: includeInactive,
        page: page,
        size: size,
      );
  @override
  List<InventoryProduct> merge(
    List<InventoryProduct> previous,
    List<InventoryProduct> next,
  ) {
    final seen = previous.map((p) => p.id).toSet();
    return [...previous, ...next.where((p) => seen.add(p.id))];
  }
}

class ProductDetailController extends InventoryScope {
  ProductDetailController(super.auth, super.repository, this.id);
  int id;
  InventoryProduct? product;
  bool loading = false;
  AppFailure? error;
  Future<void>? _pending;
  @override
  bool get permitted => access.products;
  @override
  void clear() {
    product = null;
    loading = false;
    error = null;
    _pending = null;
  }

  Future<void> selectProduct(int next) {
    if (id == next) return load();
    generation++;
    clear();
    id = next;
    return load();
  }

  @override
  Future<void> load() {
    if (disposed) return Future.value();
    if (_pending != null) return _pending!;
    if (!allowed) {
      clear();
      error = AppFailure.http(403, null);
      notifyListeners();
      return Future.value();
    }
    final value = ++generation, target = id;
    loading = true;
    error = null;
    notifyListeners();
    late final Future<void> pending;
    pending = _fetch(value, target).whenComplete(() {
      if (identical(_pending, pending)) _pending = null;
    });
    return _pending = pending;
  }

  Future<void> _fetch(int value, int target) async {
    try {
      final result = await repository.detail(access, target);
      if (current(value)) product = result;
    } catch (failure) {
      if (current(value)) {
        error = safe(failure);
        if (forbidden(error!) || error!.status == 404) {
          product = null;
          notifyListeners();
        }
        if (forbidden(error!)) await auth.checkSession();
      }
    } finally {
      if (current(value)) {
        loading = false;
        notifyListeners();
      }
    }
  }
}

class MovementController extends InventoryPaged<InventoryMovement> {
  MovementController(
    super.auth,
    super.repository,
    this.productId, {
    super.size,
  });
  final int productId;
  MovementRange range = const MovementRange.preset(MovementPeriod.month);
  @override
  bool get permitted => access.movements;
  @override
  String get contextLabel => range.label;
  void setRange(MovementRange next) {
    range = next;
    queryChanged();
  }

  @override
  Future<InventoryPage<InventoryMovement>> request(int page) =>
      repository.movements(access, productId, range, page: page, size: size);
  // The contract has no movement ID. Equal-valued events can be legitimate.
}
