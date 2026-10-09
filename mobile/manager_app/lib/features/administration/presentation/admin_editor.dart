import 'package:flutter/material.dart';

import '../../../core/errors/app_failure.dart';
import '../../../shared/widgets/states.dart';
import '../../auth/state/auth_controller.dart';
import '../../final_features/data/feature_access.dart';
import '../../final_features/presentation/feature_widgets.dart';
import '../../parties/presentation/party_widgets.dart' show PartyBlocked;
import '../../sales/presentation/sales_widgets.dart' show SalesIdentifier;
import '../data/admin_models.dart';
import '../data/admin_repository.dart';
import '../state/admin_mutation.dart';
import 'admin_confirmation.dart';

class AdminEditor extends StatefulWidget {
  const AdminEditor({
    super.key,
    required this.auth,
    required this.metadata,
    this.target,
    this.reset = false,
  });
  final AuthController auth;
  final AdminMetadata metadata;
  final AdminUser? target;
  final bool reset;
  @override
  State<AdminEditor> createState() => _AdminEditorState();
}

class _AdminEditorState extends State<AdminEditor> {
  final username = TextEditingController(),
      name = TextEditingController(),
      phone = TextEditingController(),
      email = TextEditingController(),
      password = TextEditingController(),
      confirm = TextEditingController();
  late final repository = AdminRepository(widget.auth.repository.client);
  late final mutation = AdminMutation(widget.auth);
  late String _scope;
  String? role;
  bool active = true, confirming = false, blocked = false, reading = false;
  String? localError, readback;
  String get title => widget.reset
      ? 'إعادة تعيين كلمة المرور'
      : widget.target == null
      ? 'إنشاء مستخدم'
      : 'تعديل المستخدم';
  bool permit(FeatureAccess a) =>
      a.users &&
      (widget.reset
          ? a.reset
          : widget.target == null
          ? a.create
          : a.edit);
  @override
  void initState() {
    super.initState();
    _scope = mutation.scope;
    final u = widget.target;
    username.text = u?.username ?? '';
    name.text = u?.fullName ?? '';
    phone.text = u?.phone ?? '';
    email.text = u?.email ?? '';
    role = u?.roleCode;
    active = u?.active ?? true;
    widget.auth.addListener(_changed);
  }

  void _clear() {
    for (final c in [username, name, phone, email, password, confirm]) {
      c.clear();
    }
  }

  void _changed() {
    if (_scope == mutation.scope || !mounted) return;
    _scope = mutation.scope;
    _clear();
    setState(() {
      blocked = true;
      role = null;
      localError = readback = null;
    });
  }

  @override
  void dispose() {
    widget.auth.removeListener(_changed);
    mutation.dispose();
    _clear();
    for (final c in [username, name, phone, email, password, confirm]) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _submit() async {
    if (confirming ||
        mutation.busy ||
        mutation.uncertain ||
        blocked ||
        reading ||
        !permit(FeatureAccess(widget.auth.user))) {
      return;
    }
    final selectedUsername = widget.target?.username ?? username.text.trim();
    final profile = AdminProfile(
      fullName: name.text,
      phone: phone.text,
      email: email.text,
      roleCode: role ?? '',
      active: active,
    );
    final newPassword = password.text, confirmation = confirm.text;
    try {
      if (!widget.reset) profile.body(widget.metadata);
      if (widget.reset || widget.target == null) {
        adminPassword(newPassword, confirmation, selectedUsername);
      }
    } on AppFailure catch (e) {
      setState(() => localError = e.message);
      return;
    }
    setState(() {
      confirming = true;
      localError = null;
    });
    final identity = widget.target == null
        ? '${name.text.trim()}\n$selectedUsername'
        : '${widget.target!.fullName}\n${widget.target!.username}\nمعرّف ${widget.target!.id}';
    final okToSubmit = await confirmAdminAction(
      context,
      widget.auth,
      title,
      '$identity\n${widget.reset || widget.target == null ? 'كلمة المرور مؤقتة؛ يجب تغييرها عند الدخول. إعادة التعيين تلغي الاعتمادات القديمة.' : 'سيحفظ الخادم الاسم والاتصال والدور والحالة بالكامل؛ قد يلزم الدخول مجدداً.'}',
    );
    if (!mounted) return;
    setState(() => confirming = false);
    password.clear();
    confirm.clear();
    if (!okToSubmit || blocked || !permit(FeatureAccess(widget.auth.user))) {
      return;
    }
    final ok = await mutation.run(permit, () {
      final access = FeatureAccess(widget.auth.user);
      if (widget.reset) {
        return repository.reset(
          access,
          widget.target!,
          newPassword,
          confirmation,
        );
      }
      if (widget.target != null) {
        return repository.edit(
          access,
          widget.metadata,
          widget.target!,
          profile,
        );
      }
      return repository.create(
        access,
        widget.metadata,
        selectedUsername,
        profile,
        newPassword,
        confirmation,
      );
    });
    if (!mounted) return;
    if (ok) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('أكد الخادم حفظ العملية.')));
      await widget.auth.checkSession();
      if (mounted) Navigator.pop(context);
    }
  }

  Future<void> _reread() async {
    if (reading || mutation.busy || blocked) return;
    setState(() => reading = true);
    final selectedScope = mutation.scope;
    try {
      final access = FeatureAccess(widget.auth.user);
      if (widget.target != null) {
        final u = await repository.detail(access, widget.target!.id);
        if (mounted && !blocked && selectedScope == mutation.scope) {
          setState(
            () => readback =
                '${u.username} • ${u.roleName} • ${u.active ? 'فعال' : 'معطّل'} • ${u.mustChange ? 'تغيير كلمة المرور مطلوب' : 'لا يوجد إلزام'}',
          );
          mutation.stateReread();
        }
      } else {
        final q = AdminQuery()..search = username.text.trim();
        final users = await repository.list(
          access,
          q,
          0,
          20,
          metadata: widget.metadata,
        );
        if (mounted && !blocked && selectedScope == mutation.scope) {
          final found = users.entries.items
              .where((u) => u.username == username.text.trim().toLowerCase())
              .firstOrNull;
          setState(
            () => readback = found == null
                ? 'لم يظهر اسم المستخدم في الصفحة؛ هذا لا يثبت عدم تنفيذ العملية.'
                : 'ظهر المستخدم ${found.username} بمعرّف ${found.id}.',
          );
          mutation.stateReread();
        }
      }
    } catch (_) {
      if (mounted && !blocked) {
        setState(
          () => readback = 'تعذرت إعادة القراءة؛ لا تعِد إرسال العملية.',
        );
      }
    } finally {
      if (mounted) setState(() => reading = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: Text(title)),
    body: ListenableBuilder(
      listenable: mutation,
      builder: (context, _) {
        if (blocked ||
            widget.auth.status != AuthStatus.authenticated ||
            !permit(FeatureAccess(widget.auth.user))) {
          return const PartyBlocked();
        }
        final busy = mutation.busy || confirming || reading;
        final formEnabled = !busy && !mutation.uncertain;
        final self = widget.target?.id == widget.auth.user?.id;
        return PopScope(
          canPop: !busy,
          child: ListView(
            padding: const EdgeInsets.all(20),
            children: [
              if (widget.target != null) ...[
                Text(widget.target!.fullName),
                SalesIdentifier(widget.target!.username),
                Text('معرّف ${widget.target!.id}'),
              ],
              if (!widget.reset) ...[
                TextField(
                  controller: username,
                  readOnly: widget.target != null,
                  enabled: formEnabled,
                  maxLength: 50,
                  decoration: const InputDecoration(
                    labelText: 'اسم المستخدم (ثابت بعد الإنشاء)',
                  ),
                ),
                TextField(
                  controller: name,
                  enabled: formEnabled,
                  maxLength: 100,
                  decoration: const InputDecoration(labelText: 'الاسم الكامل'),
                ),
                TextField(
                  controller: phone,
                  enabled: formEnabled,
                  maxLength: 100,
                  keyboardType: TextInputType.phone,
                  decoration: const InputDecoration(
                    labelText: 'الهاتف (اختياري)',
                  ),
                ),
                TextField(
                  controller: email,
                  enabled: formEnabled,
                  maxLength: 100,
                  keyboardType: TextInputType.emailAddress,
                  decoration: const InputDecoration(
                    labelText: 'البريد الإلكتروني (اختياري)',
                  ),
                ),
                FeatureChoice<String>(
                  label: 'الدور المعتمد',
                  value: role,
                  choices: {
                    for (final r in widget.metadata.roles) r.code: r.name,
                  },
                  enabled: formEnabled && !self,
                  onChanged: (v) => setState(() => role = v),
                ),
                if (widget.target != null)
                  SwitchListTile(
                    title: const Text('الحساب فعال'),
                    value: active,
                    onChanged: !formEnabled || self
                        ? null
                        : (v) => setState(() => active = v),
                  ),
              ],
              if (widget.reset || widget.target == null) ...[
                const Padding(
                  padding: EdgeInsets.symmetric(vertical: 12),
                  child: Text(
                    '8–128 حرفاً، حروف وأرقام، مختلفة عن اسم المستخدم. كلمة مرور مؤقتة، لا تُعرض بعد الحفظ.',
                  ),
                ),
                TextField(
                  controller: password,
                  obscureText: true,
                  enableSuggestions: false,
                  autocorrect: false,
                  enabled: !busy && !mutation.uncertain,
                  maxLength: 128,
                  decoration: const InputDecoration(
                    labelText: 'كلمة المرور المؤقتة',
                  ),
                ),
                TextField(
                  controller: confirm,
                  obscureText: true,
                  enableSuggestions: false,
                  autocorrect: false,
                  enabled: !busy && !mutation.uncertain,
                  maxLength: 128,
                  decoration: const InputDecoration(
                    labelText: 'تأكيد كلمة المرور',
                  ),
                ),
              ],
              if (localError != null) Text(localError!),
              if (mutation.error != null) ErrorNotice(mutation.error!),
              if (mutation.busy || reading) const LinearProgressIndicator(),
              if (mutation.uncertain) ...[
                OutlinedButton(
                  onPressed: busy ? null : _reread,
                  child: const Text('إعادة قراءة الحالة قبل المتابعة'),
                ),
                if (readback != null) Text(readback!),
                if (mutation.reread)
                  OutlinedButton(
                    onPressed: () async {
                      if (await confirmAction(
                        context,
                        'الإقرار بالنتيجة غير المؤكدة',
                        'ربما تم تنفيذ الطلب السابق. إعادة القراءة لا تثبت كلمة المرور الحالية. أفهم ذلك وسأختار محاولة جديدة بنفسي.',
                      )) {
                        mutation.acknowledge();
                      }
                    },
                    child: const Text('الإقرار قبل محاولة جديدة'),
                  ),
              ],
              const SizedBox(height: 16),
              FilledButton(
                onPressed: busy || mutation.uncertain ? null : _submit,
                child: Text(title),
              ),
            ],
          ),
        );
      },
    ),
  );
}
