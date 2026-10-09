import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../../core/errors/app_failure.dart';
import '../../auth/state/auth_controller.dart';
import '../../inventory/data/inventory_models.dart' show InventoryPage;
import '../data/party_access.dart';
import '../data/party_models.dart';
import '../data/party_repository.dart';

abstract class PartyScope extends ChangeNotifier {
  PartyScope(this.auth, this.repository, this.kind) {
    _scope = scope;
    auth.addListener(_changed);
  }
  final AuthController auth;
  final PartyRepository repository;
  final PartyKind kind;
  late String _scope;
  int generation = 0;
  bool disposed = false;
  PartyAccess get access => PartyAccess(auth.user, kind);
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
    if (disposed || scope == _scope) return;
    _scope = scope;
    generation++;
    clear();
    notifyListeners();
    if (allowed) unawaited(load());
  }

  AppFailure safe(Object failure) =>
      failure is AppFailure ? failure : AppFailure.malformed;
  bool denied(AppFailure failure) =>
      failure.status == 401 || failure.status == 403 || failure.status == 404;
  Future<void> reject(AppFailure failure) async {
    generation++;
    clear();
    setError(failure);
    notifyListeners();
    if (failure.status != 404) await auth.checkSession();
  }

  void setError(AppFailure failure);
  @override
  void dispose() {
    disposed = true;
    generation++;
    clear();
    auth.removeListener(_changed);
    super.dispose();
  }
}

abstract class PartyPaged<T, D> extends PartyScope {
  PartyPaged(super.auth, super.repository, super.kind, {this.size = 20});
  final int size;
  List<T> items = const [];
  int page = -1, total = 0, pages = 0;
  bool loading = false,
      loadingMore = false,
      searching = false,
      loaded = false,
      _lastNonempty = false;
  AppFailure? error, nextError;
  String loadedContext = '';
  Future<void>? _first, _next;
  Timer? _debounce;
  bool get hasMore =>
      loaded && _lastNonempty && page + 1 < pages && page < 10000;
  bool get canLoadMore =>
      allowed && hasMore && !loading && !searching && error == null;
  String get contextLabel;
  Future<D> request(int page);
  InventoryPage<T> pageOf(D result);
  void accept(D result, {required bool append});
  List<T> appendRows(List<T> incoming) => [...items, ...incoming];
  void clearData();
  @override
  void setError(AppFailure failure) => error = failure;
  @override
  void clear() {
    _debounce?.cancel();
    _debounce = null;
    _first = null;
    _next = null;
    items = const [];
    page = -1;
    total = pages = 0;
    loading = loadingMore = searching = loaded = _lastNonempty = false;
    error = nextError = null;
    loadedContext = '';
    clearData();
  }

  void queryChanged({bool debounce = false}) {
    if (disposed) return;
    generation++;
    clear();
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
  Future<void> load() {
    if (disposed) return Future.value();
    _debounce?.cancel();
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
    error = nextError = null;
    notifyListeners();
    late final Future<void> pending;
    pending = _fetch(value, label, false).whenComplete(() {
      if (identical(_first, pending)) _first = null;
    });
    return _first = pending;
  }

  Future<void> nextPage() {
    if (_next != null) return _next!;
    if (disposed || !canLoadMore) return Future.value();
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
        final entries = pageOf(result);
        accept(result, append: append);
        items = List.unmodifiable(
          append ? appendRows(entries.items) : entries.items,
        );
        page = entries.page;
        total = entries.total;
        pages = entries.pages;
        loaded = true;
        _lastNonempty = entries.items.isNotEmpty;
        loadedContext = label;
      }
    } catch (failure) {
      if (current(value)) {
        final mapped = safe(failure);
        if (denied(mapped)) {
          await reject(mapped);
        } else if (append) {
          nextError = mapped;
        } else {
          error = mapped;
        }
      }
    } finally {
      if (current(value)) {
        loading = loadingMore = false;
        notifyListeners();
      }
    }
  }
}

class PartyListController extends PartyPaged<PartyProfile, PartyListing> {
  PartyListController(
    super.auth,
    super.repository,
    super.kind, {
    super.size,
    bool outstanding = false,
  }) : mode = outstanding ? PartyMode.outstanding : PartyMode.all,
       sort = outstanding ? PartySort.balanceDescending : PartySort.name;
  PartyMode mode;
  PartySort sort;
  String search = '';
  String? totalOutstanding;
  @override
  bool get permitted =>
      access.identity && (mode == PartyMode.all || access.financial);
  @override
  String get contextLabel =>
      '${mode == PartyMode.all ? kind.title : kind.debtTitle} • ${sort.label}${search.isEmpty ? '' : ' • $search'}';
  @override
  void clearData() {
    totalOutstanding = null;
    if (!access.financial) {
      mode = PartyMode.all;
      if (sort.financial) sort = PartySort.name;
    }
  }

  void setSearch(String value, {bool immediate = false}) {
    final next = value.trim();
    if (search == next && !immediate) return;
    search = next;
    queryChanged(debounce: !immediate && next.isNotEmpty);
  }

  void setMode(PartyMode next) {
    if (next == mode || (next == PartyMode.outstanding && !access.financial)) {
      return;
    }
    mode = next;
    sort = next == PartyMode.outstanding
        ? PartySort.balanceDescending
        : PartySort.name;
    queryChanged();
  }

  void setSort(PartySort next) {
    if (next == sort || (next.financial && !access.financial)) return;
    sort = next;
    queryChanged();
  }

  @override
  Future<PartyListing> request(int page) => repository.list(
    access,
    mode: mode,
    sort: sort,
    search: search,
    page: page,
    size: size,
  );
  @override
  InventoryPage<PartyProfile> pageOf(PartyListing result) => result.entries;
  @override
  void accept(PartyListing result, {required bool append}) =>
      totalOutstanding = result.totalOutstanding;
  @override
  List<PartyProfile> appendRows(List<PartyProfile> incoming) {
    final seen = items.map((item) => item.id).toSet();
    return [...items, ...incoming.where((item) => seen.add(item.id))];
  }
}

class PartyAccountController extends PartyPaged<PartyEntry, PartyAccount> {
  PartyAccountController(
    super.auth,
    super.repository,
    super.kind,
    this.id, {
    super.size,
  });
  int id;
  PartyRange range = const PartyRange.preset(PartyPeriod.month);
  PartyAccount? account;
  @override
  bool get permitted => access.financial;
  @override
  String get contextLabel => '${kind.singular} #$id • ${range.label}';
  @override
  void clearData() => account = null;
  void selectParty(int next) {
    if (next == id) return;
    id = next;
    queryChanged();
  }

  void setRange(PartyRange next) {
    range = next;
    queryChanged();
  }

  @override
  Future<PartyAccount> request(int page) {
    // Anchor later pages to the first server-resolved range, even across midnight.
    final requested = page > 0 && account != null
        ? PartyRange.custom(account!.range.from.iso, account!.range.to.iso)
        : range;
    return repository.account(access, id, requested, page: page, size: size);
  }

  @override
  InventoryPage<PartyEntry> pageOf(PartyAccount result) => result.entries;
  @override
  void accept(PartyAccount result, {required bool append}) {
    if (append) {
      result.range.requireMatch(account!.range);
      if (items.isNotEmpty &&
          result.entries.items.isNotEmpty &&
          result.entries.items.first.date.compareTo(items.last.date) < 0) {
        throw AppFailure.malformed;
      }
    } else {
      account = result;
    }
    // No entry ID is published; identical legitimate ledger entries are retained.
    // Page totals/running do not replace the last explicit full-range header refresh.
  }
}

class PartyDetailController extends PartyScope {
  PartyDetailController(super.auth, super.repository, super.kind, this.id);
  int id;
  PartyProfile? profile;
  bool loading = false;
  AppFailure? error;
  Future<void>? _pending;
  @override
  bool get permitted => access.identity;
  @override
  void clear() {
    profile = null;
    loading = false;
    error = null;
    _pending = null;
  }

  @override
  void setError(AppFailure failure) => error = failure;
  Future<void> selectParty(int next) {
    if (next != id) {
      generation++;
      clear();
      id = next;
    }
    return load();
  }

  @override
  Future<void> load() {
    if (disposed) return Future.value();
    if (_pending != null) return _pending!;
    if (!allowed) {
      clear();
      setError(AppFailure.http(403, null));
      notifyListeners();
      return Future.value();
    }
    final value = ++generation, selected = id;
    loading = true;
    error = null;
    notifyListeners();
    late final Future<void> pending;
    pending = _fetch(value, selected).whenComplete(() {
      if (identical(_pending, pending)) _pending = null;
    });
    return _pending = pending;
  }

  Future<void> _fetch(int value, int selected) async {
    try {
      final result = await repository.detail(access, selected);
      if (current(value)) profile = result;
    } catch (failure) {
      if (current(value)) {
        final mapped = safe(failure);
        if (denied(mapped)) {
          await reject(mapped);
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
