import 'package:flutter/material.dart';

import '../../auth/state/auth_controller.dart';
import '../../final_features/data/feature_access.dart';
import '../../final_features/presentation/feature_widgets.dart';
import '../../final_features/state/feature_state.dart';
import '../../sales/presentation/sales_widgets.dart' show SalesIdentifier;
import '../data/audit_repository.dart';

class AuditScreen extends StatefulWidget {
  const AuditScreen({super.key, required this.auth});
  final AuthController auth;
  @override
  State<AuditScreen> createState() => _AuditScreenState();
}

class _AuditScreenState extends State<AuditScreen> {
  final query = AuditQuery();
  String? userError;
  late final repository = AuditRepository(widget.auth.repository.client);
  late final state = FeaturePaged<AuditEvent>(
    widget.auth,
    permit: (a) => a.audit,
    fetch: (page, size) =>
        repository.list(FeatureAccess(widget.auth.user), query, page, size),
    identity: (e) => e.id,
  );
  @override
  void initState() {
    super.initState();
    state.load();
  }

  @override
  void dispose() {
    state.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: state,
    builder: (context, _) => FeatureRows(
      state: state,
      header: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(
            'سجل العمليات',
            style: Theme.of(context).textTheme.headlineSmall,
          ),
          const Text(
            'أحداث النظام المصرّح بها فقط؛ لا تتضمن محتوى السجلات أو بيانات الأجهزة.',
          ),
          FeatureRangeControls(
            range: query.range,
            changed: (v) {
              query.range = v;
              state.queryChanged();
            },
            refresh: state.load,
            loading: state.loading,
          ),
          Wrap(
            spacing: 12,
            runSpacing: 12,
            children: [
              FeatureChoice<String?>(
                label: 'الإجراء',
                value: query.action,
                choices: {
                  null: 'كل الإجراءات',
                  for (final action in (auditActions.toList()..sort()))
                    action: action,
                },
                onChanged: (v) {
                  query.action = v;
                  state.queryChanged();
                },
              ),
              FeatureChoice<String?>(
                label: 'التصنيف',
                value: query.category,
                choices: {
                  null: 'كل التصنيفات',
                  for (final category in (auditCategories.toList()..sort()))
                    category: category,
                },
                onChanged: (v) {
                  query.category = v;
                  state.queryChanged();
                },
              ),
            ],
          ),
          TextField(
            keyboardType: TextInputType.number,
            decoration: const InputDecoration(
              labelText: 'معرّف المستخدم (اختياري)',
            ),
            onSubmitted: (v) {
              final id = v.trim().isEmpty ? null : int.tryParse(v.trim());
              if (v.trim().isNotEmpty &&
                  (id == null || id < 1 || id > 2147483647)) {
                setState(() => userError = 'أدخل معرّف مستخدم صحيحاً.');
                return;
              }
              setState(() => userError = null);
              query.userId = id;
              state.queryChanged();
            },
          ),
          if (userError != null) Text(userError!),
        ],
      ),
      row: (AuditEvent e) => Card(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text('الحدث ${e.id}'),
              SalesIdentifier(e.timestamp),
              Text(e.fullName ?? 'فاعل غير متاح'),
              if (e.username != null) SalesIdentifier(e.username!),
              if (e.userId != null) Text('معرّف المستخدم ${e.userId}'),
              SalesIdentifier(e.action == 'OTHER' ? 'حدث آخر' : e.action),
              Text(e.category == 'OTHER' ? 'تصنيف آخر' : e.category),
            ],
          ),
        ),
      ),
    ),
  );
}
