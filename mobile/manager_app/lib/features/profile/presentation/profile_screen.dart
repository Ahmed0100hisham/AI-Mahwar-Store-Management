import 'dart:async';

import 'package:flutter/material.dart';

import '../../../core/errors/app_failure.dart';
import '../../../shared/widgets/states.dart';
import '../../auth/data/auth_models.dart';
import '../../auth/presentation/change_password_screen.dart';
import '../../auth/state/auth_controller.dart';
import '../../sales/presentation/sales_widgets.dart' show isolateDate;

class ProfileScreen extends StatefulWidget {
  const ProfileScreen({super.key, required this.auth});
  final AuthController auth;
  @override
  State<ProfileScreen> createState() => _ProfileScreenState();
}

class _ProfileScreenState extends State<ProfileScreen> {
  List<DeviceSession>? _sessions;
  AppFailure? _error;
  bool _loading = true, _acting = false;
  Future<void>? _pending;
  int _generation = 0;
  late String _scope;
  String get _authScope =>
      '${widget.auth.status}:${widget.auth.sessionEpoch}:${widget.auth.user?.id}';
  @override
  void initState() {
    super.initState();
    _scope = _authScope;
    widget.auth.addListener(_authChanged);
    _load();
  }

  @override
  void dispose() {
    widget.auth.removeListener(_authChanged);
    ++_generation;
    super.dispose();
  }

  void _authChanged() {
    if (_scope == _authScope) return;
    _scope = _authScope;
    ++_generation;
    _pending = null;
    setState(() {
      _sessions = null;
      _error = null;
      _loading = true;
    });
    if (widget.auth.status == AuthStatus.authenticated) {
      unawaited(_load());
    }
  }

  Future<void> _load() {
    if (_pending != null) return _pending!;
    if (!mounted || widget.auth.status != AuthStatus.authenticated) {
      return Future.value();
    }
    late final Future<void> pending;
    pending = _fetch(++_generation, _scope).whenComplete(() {
      if (identical(_pending, pending)) _pending = null;
    });
    return _pending = pending;
  }

  bool _current(int generation, String scope) =>
      mounted && generation == _generation && scope == _authScope;

  Future<void> _fetch(int generation, String scope) async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      await widget.auth.revalidate();
      if (!_current(generation, scope)) return;
      final sessions = await widget.auth.repository.sessions();
      if (_current(generation, scope)) {
        setState(() => _sessions = sessions);
      }
    } on AppFailure catch (error) {
      if (_current(generation, scope)) {
        setState(() {
          _error = error;
          if (error.status == 401 || error.status == 403) _sessions = null;
        });
      }
    } catch (_) {
      if (_current(generation, scope)) {
        setState(() => _error = AppFailure.malformed);
      }
    } finally {
      if (_current(generation, scope)) {
        setState(() => _loading = false);
      }
    }
  }

  Future<void> _revoke(DeviceSession session) async {
    if (_acting ||
        !await confirmAction(
          context,
          'إنهاء جلسة الجهاز',
          'سيحتاج هذا الجهاز إلى تسجيل الدخول مجدداً.',
        )) {
      return;
    }
    if (!mounted) {
      return;
    }
    setState(() => _acting = true);
    try {
      await widget.auth.repository.revoke(session.sid);
      await _load();
    } on AppFailure catch (error) {
      if (mounted) {
        setState(() => _error = error);
      }
    } finally {
      if (mounted) {
        setState(() => _acting = false);
      }
    }
  }

  String _date(DateTime date) => date.toLocal().toString().substring(0, 16);
  @override
  Widget build(BuildContext context) {
    final user = widget.auth.user;
    if (user == null) {
      return const SizedBox.shrink();
    }
    return RefreshIndicator(
      onRefresh: _load,
      child: ListView(
        padding: const EdgeInsets.all(24),
        children: [
          Text(
            'الملف الشخصي والأجهزة',
            style: Theme.of(context).textTheme.headlineSmall
                ?.copyWith(fontWeight: FontWeight.w700),
          ),
          const SizedBox(height: 8),
          const Text('راجع معلومات حسابك وتحكّم في جلساتك الخاصة.'),
          const SizedBox(height: 24),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(20),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    user.fullName,
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                  const SizedBox(height: 8),
                  Text('${user.username} • ${user.roleName}'),
                  const SizedBox(height: 16),
                  Wrap(
                    spacing: 12,
                    runSpacing: 12,
                    children: [
                      OutlinedButton.icon(
                        onPressed: widget.auth.busy
                            ? null
                            : () async {
                                widget.auth.clearError();
                                await Navigator.push<void>(
                                  context,
                                  MaterialPageRoute(
                                    builder: (_) =>
                                        ChangePasswordScreen(auth: widget.auth),
                                  ),
                                );
                              },
                        icon: const Icon(Icons.lock_reset),
                        label: const Text('تغيير كلمة المرور'),
                      ),
                      OutlinedButton.icon(
                        onPressed: widget.auth.busy
                            ? null
                            : () async {
                                if (await confirmAction(
                                  context,
                                  'تسجيل الخروج من كل الأجهزة',
                                  'سيتم إنهاء جميع جلساتك بما فيها هذه الجلسة.',
                                )) {
                                  await widget.auth.logout(all: true);
                                }
                              },
                        icon: const Icon(Icons.logout),
                        label: const Text('الخروج من كل الأجهزة'),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 28),
          Row(
            children: [
              Expanded(
                child: Text(
                  'أجهزتك وجلساتك',
                  style: Theme.of(context).textTheme.titleLarge,
                ),
              ),
              IconButton(
                onPressed: _loading ? null : _load,
                tooltip: 'تحديث الجلسات',
                icon: const Icon(Icons.refresh),
              ),
            ],
          ),
          const SizedBox(height: 16),
          if (_error != null)
            ErrorNotice(_error!, onRetry: _load)
          else if (_loading)
            const Padding(
              padding: EdgeInsets.all(32),
              child: BusyView(label: 'جارٍ تحميل الجلسات…'),
            )
          else if (_sessions!.isEmpty)
            const EmptyView(
              title: 'لا توجد جلسات',
              message: 'لا توجد أجهزة مسجلة حالياً.',
            )
          else
            ..._sessions!.map(
              (session) => Padding(
                padding: const EdgeInsets.only(bottom: 12),
                child: Card(
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            const Icon(Icons.devices_outlined),
                            const SizedBox(width: 12),
                            Expanded(
                              child: Text(
                                session.deviceLabel?.isNotEmpty == true
                                    ? session.deviceLabel!
                                    : 'جهاز بدون اسم',
                                style: Theme.of(context).textTheme.titleMedium,
                              ),
                            ),
                          ],
                        ),
                        if (session.current)
                          const Align(
                            alignment: AlignmentDirectional.centerStart,
                            child: Chip(label: Text('هذا الجهاز')),
                          ),
                        const SizedBox(height: 12),
                        Text(
                          'آخر نشاط: ${isolateDate(_date(session.lastActivityAt))}',
                        ),
                        Text(
                          'بدء الجلسة: ${isolateDate(_date(session.createdAt))}',
                        ),
                        Text(
                          'الحالة: ${switch (session.status) {
                            'ACTIVE' => 'نشطة',
                            'REVOKED' => 'منتهية',
                            'EXPIRED' => 'انتهت صلاحيتها',
                            _ => 'غير معروفة',
                          }}',
                        ),
                        if (!session.current && session.status == 'ACTIVE')
                          Align(
                            alignment: AlignmentDirectional.centerEnd,
                            child: TextButton(
                              onPressed: _acting
                                  ? null
                                  : () => _revoke(session),
                              child: const Text('إنهاء الجلسة'),
                            ),
                          ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          const SizedBox(height: 24),
          const Text(
            'تظهر هنا جلسات حسابك فقط. قد تظل الجلسات السابقة ظاهرة وفق سياسة الاحتفاظ في الخدمة.',
          ),
        ],
      ),
    );
  }
}
