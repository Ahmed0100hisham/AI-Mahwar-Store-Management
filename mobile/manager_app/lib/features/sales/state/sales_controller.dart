import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../../core/errors/app_failure.dart';
import '../../auth/state/auth_controller.dart';
import '../../inventory/data/inventory_models.dart' show InventoryPage;
import '../data/sales_access.dart';
import '../data/sales_models.dart';
import '../data/sales_repository.dart';

abstract class SalesScope extends ChangeNotifier {
  SalesScope(this.auth, this.repository) {
    _scope = scope;
    auth.addListener(_changed);
  }
  final AuthController auth;
  final SalesRepository repository;
  late String _scope;
  int generation = 0;
  bool disposed = false;
  SalesAccess get access => SalesAccess(auth.user);
  bool get permitted;
  bool get allowed => auth.status == AuthStatus.authenticated && permitted;
  String get scope {
    final codes = auth.user?.permissions.toList() ?? <String>[];
    codes.sort();
    return '${auth.status}:${auth.sessionEpoch}:${auth.user?.id}:${codes.join(',')}';
  }

  bool current(int value) =>
      !disposed && allowed && generation == value && _scope == scope;
  void clear();
  Future<void> load();
  void _changed() {
    if (disposed || scope == _scope) {
      return;
    }
    _scope = scope;
    generation++;
    clear();
    notifyListeners();
    if (allowed) {
      unawaited(load());
    }
  }

  AppFailure safe(Object error) =>
      error is AppFailure ? error : AppFailure.malformed;
  bool denied(AppFailure error) => error.status == 401 || error.status == 403;
  @override
  void dispose() {
    disposed = true;
    generation++;
    clear();
    auth.removeListener(_changed);
    super.dispose();
  }
}

class SalesOverviewController extends SalesScope {
  SalesOverviewController(super.auth, super.repository);
  SalesRange range = const SalesRange.preset(SalesPeriod.month);
  SalesReport? data;
  AppFailure? error;
  bool loading = false;
  Future<void>? _pending;
  @override
  bool get permitted => access.reports;
  @override
  void clear() {
    data = null;
    error = null;
    loading = false;
    _pending = null;
  }

  void setRange(SalesRange next) {
    generation++;
    _pending = null;
    range = next;
    unawaited(load());
  }

  @override
  Future<void> load() {
    if (disposed) {
      return Future.value();
    }
    if (_pending != null) {
      return _pending!;
    }
    if (!allowed) {
      clear();
      error = AppFailure.http(403, null);
      notifyListeners();
      return Future.value();
    }
    final value = ++generation;
    loading = true;
    error = null;
    notifyListeners();
    late final Future<void> pending;
    pending = _fetch(value).whenComplete(() {
      if (identical(_pending, pending)) {
        _pending = null;
      }
    });
    return _pending = pending;
  }

  Future<void> _fetch(int value) async {
    try {
      final result = await repository.report(access, range);
      if (current(value)) {
        data = result;
      }
    } catch (failure) {
      if (current(value)) {
        final mapped = safe(failure);
        if (denied(mapped)) {
          generation++;
          clear();
          error = mapped;
          notifyListeners();
          await auth.checkSession();
        } else {
          error = mapped;
        }
      }
    } finally {
      if (current(value)) {
        loading = false;
        notifyListeners();
      }
    }
  }
}

class InvoiceListController extends SalesScope {
  InvoiceListController(super.auth, super.repository, {this.size = 20});
  final int size;
  SalesRange range = const SalesRange.preset(SalesPeriod.month);
  InvoiceSort sort = InvoiceSort.newest;
  InvoiceStatus? status;
  InvoicePayment? payment;
  InvoiceCustomer? customer;
  String search = '', loadedContext = '';
  List<SalesInvoice> items = const [];
  int page = -1, total = 0, pages = 0;
  bool loading = false,
      loadingMore = false,
      searching = false,
      loaded = false,
      _lastNonempty = false;
  AppFailure? error, nextError;
  Future<void>? _first, _next;
  Timer? _debounce;
  @override
  bool get permitted => access.invoices;
  bool get hasMore =>
      loaded && _lastNonempty && page + 1 < pages && page < 10000;
  bool get canLoadMore => hasMore && !loading && !searching && error == null;
  String get contextLabel =>
      '${range.label} • ${status?.label ?? 'كل الحالات'} • ${payment?.label ?? 'كل طرق الدفع'} • ${sort.label}${search.isEmpty ? '' : ' • $search'}${customer == null ? '' : ' • ${customer!.name}'}';
  @override
  void clear() {
    _debounce?.cancel();
    _debounce = null;
    _first = null;
    _next = null;
    items = const [];
    page = -1;
    total = 0;
    pages = 0;
    loading = false;
    loadingMore = false;
    searching = false;
    loaded = false;
    _lastNonempty = false;
    error = null;
    nextError = null;
    loadedContext = '';
  }

  void _query({bool delay = false}) {
    if (disposed) {
      return;
    }
    _debounce?.cancel();
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
      _debounce = Timer(const Duration(milliseconds: 350), () {
        searching = false;
        unawaited(load());
      });
    } else {
      unawaited(load());
    }
  }

  void setSearch(String value, {bool immediate = false}) {
    final next = value.trim();
    if (search == next && !immediate) {
      return;
    }
    search = next;
    _query(delay: !immediate && next.isNotEmpty);
  }

  void setRange(SalesRange next) {
    range = next;
    _query();
  }

  void setStatus(InvoiceStatus? next) {
    if (status != next) {
      status = next;
      _query();
    }
  }

  void setPayment(InvoicePayment? next) {
    if (payment != next) {
      payment = next;
      _query();
    }
  }

  void setSort(InvoiceSort next) {
    if (sort != next) {
      sort = next;
      _query();
    }
  }

  void setCustomer(InvoiceCustomer? next) {
    customer = next;
    _query();
  }

  @override
  Future<void> load() {
    if (disposed) {
      return Future.value();
    }
    _debounce?.cancel();
    searching = false;
    if (_first != null) {
      return _first!;
    }
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
      if (identical(_first, pending)) {
        _first = null;
      }
    });
    return _first = pending;
  }

  Future<void> nextPage() {
    if (_next != null) {
      return _next!;
    }
    if (disposed || !allowed || !canLoadMore) {
      return Future.value();
    }
    final value = generation;
    loadingMore = true;
    nextError = null;
    notifyListeners();
    late final Future<void> pending;
    pending = _fetch(value, loadedContext, true).whenComplete(() {
      if (identical(_next, pending)) {
        _next = null;
      }
    });
    return _next = pending;
  }

  Future<void> _fetch(int value, String label, bool append) async {
    try {
      final result = await repository.invoices(
        access,
        range,
        search: search,
        status: status,
        payment: payment,
        sort: sort,
        customerId: customer?.id,
        page: append ? page + 1 : 0,
        size: size,
      );
      if (current(value)) {
        final seen = items.map((i) => i.id).toSet();
        items = List.unmodifiable(
          append
              ? [...items, ...result.items.where((i) => seen.add(i.id))]
              : result.items,
        );
        page = result.page;
        total = result.total;
        pages = result.pages;
        loaded = true;
        _lastNonempty = result.items.isNotEmpty;
        loadedContext = label;
      }
    } catch (failure) {
      if (current(value)) {
        final mapped = safe(failure);
        if (denied(mapped)) {
          generation++;
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

// Each nested collection has its own page cursor and singleflight operation.
class DetailCollection<T> {
  List<T> items = const [];
  int page = -1, total = 0, pages = 0;
  bool busy = false, lastNonempty = false;
  AppFailure? error;
  Future<void>? pending;
  bool get hasMore =>
      page >= 0 && lastNonempty && page + 1 < pages && page < 10000;
  void clear() {
    items = const [];
    page = -1;
    total = 0;
    pages = 0;
    busy = false;
    lastNonempty = false;
    error = null;
    pending = null;
  }

  void apply(
    InventoryPage<T> result,
    int Function(T) identity, {
    required bool append,
  }) {
    final seen = items.map(identity).toSet();
    items = List.unmodifiable(
      append
          ? [...items, ...result.items.where((i) => seen.add(identity(i)))]
          : result.items,
    );
    page = result.page;
    total = result.total;
    pages = result.pages;
    lastNonempty = result.items.isNotEmpty;
  }
}

class InvoiceDetailController extends SalesScope {
  InvoiceDetailController(
    super.auth,
    super.repository,
    this.id, {
    this.size = 20,
  });
  int id;
  final int size;
  SalesInvoice? invoice;
  final lines = DetailCollection<InvoiceLine>(),
      returns = DetailCollection<InvoiceReturn>();
  bool loading = false;
  AppFailure? error;
  Future<void>? _first;
  @override
  bool get permitted => access.invoices;
  @override
  void clear() {
    invoice = null;
    lines.clear();
    returns.clear();
    loading = false;
    error = null;
    _first = null;
  }

  Future<void> selectInvoice(int next) {
    if (id != next) {
      generation++;
      clear();
      id = next;
    }
    return load();
  }

  @override
  Future<void> load() {
    if (disposed) {
      return Future.value();
    }
    if (_first != null) {
      return _first!;
    }
    if (!allowed) {
      clear();
      error = AppFailure.http(403, null);
      notifyListeners();
      return Future.value();
    }
    final value = ++generation;
    loading = true;
    error = null;
    lines.pending = null;
    returns.pending = null;
    lines.busy = false;
    returns.busy = false;
    lines.error = null;
    returns.error = null;
    notifyListeners();
    late final Future<void> pending;
    pending = _fetch(value, null).whenComplete(() {
      if (identical(_first, pending)) {
        _first = null;
      }
    });
    return _first = pending;
  }

  Future<void> nextLines() => _next(false);
  Future<void> nextReturns() => _next(true);
  Future<void> _next(bool returned) {
    final collection = returned ? returns : lines;
    if (collection.pending != null) {
      return collection.pending!;
    }
    if (disposed ||
        !allowed ||
        loading ||
        error != null ||
        !collection.hasMore) {
      return Future.value();
    }
    final value = generation;
    collection.busy = true;
    collection.error = null;
    notifyListeners();
    late final Future<void> pending;
    pending = _fetch(value, returned).whenComplete(() {
      if (identical(collection.pending, pending)) {
        collection.pending = null;
      }
    });
    return collection.pending = pending;
  }

  Future<void> _fetch(int value, bool? returned) async {
    try {
      final result = await repository.detail(
        access,
        id,
        page: returned == false ? lines.page + 1 : 0,
        size: size,
        returnsPage: returned == true ? returns.page + 1 : 0,
        returnsSize: size,
      );
      if (current(value)) {
        if (returned == null) {
          invoice = result.invoice;
          lines.apply(result.items, (i) => i.id, append: false);
          returns.apply(result.returns, (i) => i.id, append: false);
        } else if (returned) {
          returns.apply(result.returns, (i) => i.id, append: true);
        } else {
          lines.apply(result.items, (i) => i.id, append: true);
        }
      }
    } catch (failure) {
      if (current(value)) {
        final mapped = safe(failure);
        if (denied(mapped) || mapped.status == 404) {
          generation++;
          clear();
          error = mapped;
          notifyListeners();
          if (denied(mapped)) {
            await auth.checkSession();
          }
        } else if (returned == null) {
          error = mapped;
        } else if (returned) {
          returns.error = mapped;
        } else {
          lines.error = mapped;
        }
      }
    } finally {
      if (current(value)) {
        if (returned == null) {
          loading = false;
        } else if (returned) {
          returns.busy = false;
        } else {
          lines.busy = false;
        }
        notifyListeners();
      }
    }
  }
}
