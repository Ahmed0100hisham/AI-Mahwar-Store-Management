import 'package:flutter/material.dart';

import '../../../shared/widgets/password_field.dart';
import '../../../shared/widgets/states.dart';
import '../state/auth_controller.dart';

class ChangePasswordScreen extends StatefulWidget {
  const ChangePasswordScreen({
    super.key,
    required this.auth,
    this.restricted = false,
  });
  final AuthController auth;
  final bool restricted;
  @override
  State<ChangePasswordScreen> createState() => _ChangePasswordScreenState();
}

class _ChangePasswordScreenState extends State<ChangePasswordScreen> {
  final _form = GlobalKey<FormState>();
  final _current = TextEditingController(),
      _next = TextEditingController(),
      _confirm = TextEditingController();
  String? _required(String? v) => v == null || v.isEmpty
      ? 'هذا الحقل مطلوب.'
      : v.length > 128
      ? 'الحد الأقصى 128 حرفاً.'
      : null;
  Future<void> _submit() async {
    if (widget.auth.busy || !_form.currentState!.validate()) {
      return;
    }
    final current = _current.text, next = _next.text, confirm = _confirm.text;
    _current.clear();
    _next.clear();
    _confirm.clear();
    final success = await widget.auth.changePassword(current, next, confirm);
    if (success && mounted) {
      if (!widget.restricted) {
        Navigator.pop(context);
      }
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text(
            'تم تغيير كلمة المرور. سجّل الدخول مجدداً بكلمة المرور الجديدة.',
          ),
        ),
      );
    }
  }

  @override
  void dispose() {
    _current.dispose();
    _next.dispose();
    _confirm.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: widget.auth,
    builder: (context, _) => PopScope(
      canPop: !widget.restricted && !widget.auth.busy,
      child: Scaffold(
        appBar: AppBar(
          title: const Text('تغيير كلمة المرور'),
          automaticallyImplyLeading: !widget.restricted,
          actions: [
            if (widget.restricted)
              TextButton(
                onPressed: widget.auth.busy ? null : () => widget.auth.logout(),
                child: const Text('تسجيل الخروج'),
              ),
          ],
        ),
        body: SafeArea(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: Center(
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 480),
                child: Card(
                  child: Padding(
                    padding: const EdgeInsets.all(24),
                    child: Form(
                      key: _form,
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          const Icon(Icons.lock_reset, size: 44),
                          const SizedBox(height: 16),
                          Text(
                            widget.restricted
                                ? 'خطوة ضرورية لحماية حسابك'
                                : 'كلمة مرور جديدة لحسابك',
                            style: Theme.of(context).textTheme.titleLarge,
                          ),
                          const SizedBox(height: 12),
                          const Text(
                            'بعد النجاح ستنتهي جميع الجلسات وستحتاج إلى تسجيل الدخول مجدداً. تتحقق الخدمة من سياسة كلمة المرور.',
                          ),
                          const SizedBox(height: 24),
                          if (widget.auth.error != null) ...[
                            ErrorNotice(widget.auth.error!),
                            const SizedBox(height: 16),
                          ],
                          PasswordField(
                            controller: _current,
                            label: 'كلمة المرور الحالية',
                            enabled: !widget.auth.busy,
                            validator: _required,
                          ),
                          const SizedBox(height: 16),
                          PasswordField(
                            controller: _next,
                            label: 'كلمة المرور الجديدة',
                            enabled: !widget.auth.busy,
                            validator: _required,
                          ),
                          const SizedBox(height: 16),
                          PasswordField(
                            controller: _confirm,
                            label: 'تأكيد كلمة المرور الجديدة',
                            enabled: !widget.auth.busy,
                            action: TextInputAction.done,
                            onSubmitted: (_) => _submit(),
                            validator: (v) =>
                                _required(v) ??
                                (v != _next.text
                                    ? 'كلمتا المرور غير متطابقتين.'
                                    : null),
                          ),
                          const SizedBox(height: 24),
                          FilledButton(
                            onPressed: widget.auth.busy ? null : _submit,
                            child: Text(
                              widget.auth.busy
                                  ? 'جارٍ التغيير…'
                                  : 'حفظ كلمة المرور',
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    ),
  );
}
