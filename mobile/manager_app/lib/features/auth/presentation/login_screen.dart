import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../../shared/widgets/password_field.dart';
import '../../../shared/widgets/states.dart';
import '../state/auth_controller.dart';

class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key, required this.auth});
  final AuthController auth;
  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  final _form = GlobalKey<FormState>();
  final _username = TextEditingController(),
      _password = TextEditingController();
  Future<void> _submit() async {
    if (widget.auth.busy || !_form.currentState!.validate()) {
      return;
    }
    final password = _password.text;
    _password.clear();
    final success = await widget.auth.login(_username.text, password);
    if (success) {
      TextInput.finishAutofillContext(shouldSave: false);
    }
  }

  @override
  void dispose() {
    _username.dispose();
    _password.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final form = ConstrainedBox(
      constraints: const BoxConstraints(maxWidth: 440),
      child: Card(
        child: Padding(
          padding: const EdgeInsets.all(28),
          child: AutofillGroup(
            child: Form(
              key: _form,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Align(
                    alignment: AlignmentDirectional.centerStart,
                    child: Container(
                      padding: const EdgeInsets.all(12),
                      decoration: BoxDecoration(
                        color: theme.colorScheme.primaryContainer,
                        borderRadius: BorderRadius.circular(14),
                      ),
                      child: Icon(
                        Icons.shield_outlined,
                        color: theme.colorScheme.primary,
                        size: 30,
                      ),
                    ),
                  ),
                  const SizedBox(height: 24),
                  Text(
                    'مرحباً بك',
                    style: theme.textTheme.headlineMedium?.copyWith(
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    'سجّل الدخول للوصول إلى مساحة الإدارة.',
                    style: theme.textTheme.bodyLarge,
                  ),
                  const SizedBox(height: 28),
                  if (widget.auth.error != null) ...[
                    ErrorNotice(
                      widget.auth.error!,
                      onRetry: widget.auth.canRestore
                          ? () => widget.auth.restore()
                          : null,
                    ),
                    const SizedBox(height: 20),
                  ],
                  TextFormField(
                    controller: _username,
                    enabled: !widget.auth.busy,
                    textInputAction: TextInputAction.next,
                    autofillHints: const [AutofillHints.username],
                    autocorrect: false,
                    decoration: const InputDecoration(
                      labelText: 'اسم المستخدم',
                      prefixIcon: Icon(Icons.person_outline),
                    ),
                    validator: (v) => v == null || v.trim().isEmpty
                        ? 'أدخل اسم المستخدم.'
                        : v.trim().length > 50
                        ? 'اسم المستخدم طويل جداً.'
                        : null,
                  ),
                  const SizedBox(height: 16),
                  PasswordField(
                    controller: _password,
                    label: 'كلمة المرور',
                    enabled: !widget.auth.busy,
                    action: TextInputAction.done,
                    autofillHints: const [AutofillHints.password],
                    validator: (v) => v == null || v.isEmpty
                        ? 'أدخل كلمة المرور.'
                        : v.length > 128
                        ? 'كلمة المرور طويلة جداً.'
                        : null,
                    onSubmitted: (_) => _submit(),
                  ),
                  const SizedBox(height: 24),
                  FilledButton(
                    onPressed: widget.auth.busy ? null : _submit,
                    child: widget.auth.busy
                        ? const SizedBox(
                            width: 22,
                            height: 22,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Text('تسجيل الدخول'),
                  ),
                  const SizedBox(height: 20),
                  Text(
                    'بيانات الدخول خاصة بك. لا تشاركها مع أي شخص.',
                    textAlign: TextAlign.center,
                    style: theme.textTheme.bodySmall,
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
    return Scaffold(
      body: SafeArea(
        child: LayoutBuilder(
          builder: (context, constraints) => SingleChildScrollView(
            padding: EdgeInsets.all(constraints.maxWidth > 800 ? 48 : 20),
            child: ConstrainedBox(
              constraints: BoxConstraints(
                minHeight: (constraints.maxHeight - 96).clamp(
                  0,
                  double.infinity,
                ),
              ),
              child: Center(
                child: constraints.maxWidth > 950
                    ? Row(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          Flexible(child: form),
                          const SizedBox(width: 64),
                          Flexible(
                            child: Padding(
                              padding: const EdgeInsets.all(24),
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  Icon(
                                    Icons.space_dashboard_outlined,
                                    size: 64,
                                    color: theme.colorScheme.primary,
                                  ),
                                  const SizedBox(height: 24),
                                  Text(
                                    'مساحة واحدة.\nرؤية أوضح.',
                                    style: theme.textTheme.displaySmall
                                        ?.copyWith(
                                          height: 1.5,
                                          fontWeight: FontWeight.w700,
                                        ),
                                  ),
                                  const SizedBox(height: 16),
                                  Text(
                                    'دخول آمن، صلاحيات واضحة، وأجهزتك تحت سيطرتك.',
                                    style: theme.textTheme.titleMedium,
                                  ),
                                  const SizedBox(height: 32),
                                  const Row(
                                    children: [
                                      Icon(Icons.verified_user_outlined),
                                      SizedBox(width: 12),
                                      Text('جلسات محمية • إدارة الأجهزة'),
                                    ],
                                  ),
                                ],
                              ),
                            ),
                          ),
                        ],
                      )
                    : form,
              ),
            ),
          ),
        ),
      ),
    );
  }
}
