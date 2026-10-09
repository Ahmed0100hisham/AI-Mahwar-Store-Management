import 'package:flutter/material.dart';

import '../../../shared/widgets/states.dart';
import '../../auth/state/auth_controller.dart';
import '../../final_features/data/feature_access.dart';
import '../../final_features/presentation/feature_widgets.dart';
import '../../final_features/state/feature_state.dart';
import '../../sales/presentation/sales_widgets.dart'
    show SalesIdentifier, isolateDate;
import '../data/admin_models.dart';
import '../data/admin_repository.dart';
import '../state/admin_mutation.dart';
import 'admin_editor.dart';
import 'admin_confirmation.dart';

class AdminScreen extends StatefulWidget {
  const AdminScreen({super.key, required this.auth});
  final AuthController auth;
  @override
  State<AdminScreen> createState() => _AdminScreenState();
}

class _AdminScreenState extends State<AdminScreen> {
  final query = AdminQuery();
  late final repository = AdminRepository(widget.auth.repository.client);
  late final FeaturePaged<AdminUser> state = FeaturePaged<AdminUser>(
    widget.auth,
    permit: (a) => a.users,
    identity: (u) => u.id,
    fetch: (page, size) async {
      final access = FeatureAccess(widget.auth.user);
      final metadata = page == 0
          ? await repository.metadata(access)
          : state.data!.header as AdminMetadata;
      final result = await repository.list(
        access,
        query,
        page,
        size,
        metadata: metadata,
      );
      return FeaturePage(result.entries, header: metadata);
    },
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

  Future<void> _open(AdminUser? user) async {
    final metadata = state.data?.header as AdminMetadata?;
    if (metadata == null) return;
    if (user == null) {
      await Navigator.push(
        context,
        MaterialPageRoute<void>(
          builder: (_) => AdminEditor(auth: widget.auth, metadata: metadata),
        ),
      );
    } else {
      await Navigator.push(
        context,
        MaterialPageRoute<void>(
          builder: (_) => AdminDetailScreen(
            auth: widget.auth,
            id: user.id,
            metadata: metadata,
          ),
        ),
      );
    }
    if (mounted) await state.load();
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: state,
    builder: (context, _) {
      final metadata = state.data?.header as AdminMetadata?;
      return FeatureRows(
        state: state,
        header: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
              'إدارة المستخدمين',
              style: Theme.of(context).textTheme.headlineSmall,
            ),
            const Text(
              'لا يوجد حذف دائم. حماية آخر مدير فعال والتحقق من الأدوار مسؤولية الخادم.',
            ),
            if (state.access.create && metadata != null)
              FilledButton.icon(
                onPressed: () => _open(null),
                icon: const Icon(Icons.person_add),
                label: const Text('إنشاء مستخدم'),
              ),
            TextField(
              maxLength: 100,
              decoration: const InputDecoration(
                labelText: 'البحث باسم المستخدم أو الاسم الكامل',
                counterText: '',
              ),
              onChanged: (v) {
                query.search = v;
                state.queryChanged(debounce: true);
              },
              onSubmitted: (v) {
                query.search = v;
                state.queryChanged();
              },
            ),
            Wrap(
              spacing: 12,
              runSpacing: 12,
              children: [
                if (metadata != null)
                  FeatureChoice<String?>(
                    label: 'الدور',
                    value: query.role,
                    choices: {
                      null: 'كل الأدوار',
                      for (final r in metadata.roles) r.code: r.name,
                    },
                    onChanged: (v) {
                      query.role = v;
                      state.queryChanged();
                    },
                  ),
                FeatureChoice<bool?>(
                  label: 'حالة الحساب',
                  value: query.active,
                  choices: const {
                    null: 'كل الحسابات',
                    true: 'فعال',
                    false: 'معطّل',
                  },
                  onChanged: (v) {
                    query.active = v;
                    state.queryChanged();
                  },
                ),
                FeatureChoice<AdminSort>(
                  label: 'الترتيب',
                  value: query.sort,
                  choices: {for (final v in AdminSort.values) v: v.label},
                  onChanged: (v) {
                    if (v != null) {
                      query.sort = v;
                      state.queryChanged();
                    }
                  },
                ),
              ],
            ),
            OutlinedButton(
              onPressed: state.loading ? null : state.load,
              child: const Text('تحديث البيانات'),
            ),
            if (metadata != null)
              ExpansionTile(
                title: const Text('الأدوار والصلاحيات المعتمدة (للقراءة)'),
                children: [
                  const Text('تعيين دور ثابت فقط؛ لا يوجد محرر صلاحيات فردية.'),
                  for (final r in metadata.roles)
                    ExpansionTile(
                      title: Text(r.name),
                      children: [
                        for (final p in metadata.permissions.where(
                          (p) => p.roles.contains(r.code),
                        ))
                          ListTile(
                            title: Text(p.description),
                            subtitle: Text('${p.group} • ${p.code}'),
                          ),
                      ],
                    ),
                ],
              ),
          ],
        ),
        row: (AdminUser u) => Card(
          child: ListTile(
            title: Text(u.fullName),
            subtitle: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                SalesIdentifier(u.username),
                Text('${u.roleName} • ${u.active ? 'فعال' : 'معطّل'}'),
                if (u.mustChange) const Text('مطلوب تغيير كلمة المرور'),
              ],
            ),
            trailing: const Icon(Icons.chevron_left),
            onTap: () => _open(u),
          ),
        ),
      );
    },
  );
}

class AdminDetailScreen extends StatefulWidget {
  const AdminDetailScreen({
    super.key,
    required this.auth,
    required this.id,
    required this.metadata,
  });
  final AuthController auth;
  final int id;
  final AdminMetadata metadata;
  @override
  State<AdminDetailScreen> createState() => _AdminDetailScreenState();
}

class _AdminDetailScreenState extends State<AdminDetailScreen> {
  late final repository = AdminRepository(widget.auth.repository.client);
  late final state = FeatureState<AdminUser>(
    widget.auth,
    permit: (a) => a.users,
    request: () =>
        repository.detail(FeatureAccess(widget.auth.user), widget.id),
  );
  late final mutation = AdminMutation(widget.auth);
  bool confirming = false;
  @override
  void initState() {
    super.initState();
    state.load();
  }

  @override
  void dispose() {
    state.dispose();
    mutation.dispose();
    super.dispose();
  }

  Future<void> _active(AdminUser u) async {
    if (confirming || mutation.busy || mutation.uncertain) {
      return;
    }
    setState(() => confirming = true);
    final confirmed = await confirmAdminAction(
      context,
      widget.auth,
      u.active ? 'تعطيل المستخدم' : 'تفعيل المستخدم',
      '${u.fullName}\n${u.username}\nمعرّف ${u.id}\n${u.active ? 'سيُمنع الدخول وتُلغى الجلسات القديمة.' : 'يتطلب دخولاً جديداً؛ الجلسات القديمة لا تعود.'}',
    );
    if (!mounted) {
      return;
    }
    setState(() => confirming = false);
    if (!confirmed || !state.allowed) {
      return;
    }
    final ok = await mutation.run(
      (a) => a.edit,
      () => repository.active(FeatureAccess(widget.auth.user), u, !u.active),
    );
    if (!mounted) return;
    if (ok) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('أكد الخادم تنفيذ العملية.')),
      );
      state.invalidate();
      await state.load();
    }
  }

  Future<void> _editor(AdminUser u, {bool reset = false}) async {
    await Navigator.push(
      context,
      MaterialPageRoute<void>(
        builder: (_) => AdminEditor(
          auth: widget.auth,
          metadata: widget.metadata,
          target: u,
          reset: reset,
        ),
      ),
    );
    if (mounted) {
      state.invalidate();
      await state.load();
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('تفاصيل المستخدم')),
    body: ListenableBuilder(
      listenable: Listenable.merge([state, mutation]),
      builder: (context, _) => FeatureBody<AdminUser>(
        state: state,
        header: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            OutlinedButton(
              onPressed: state.loading || mutation.busy
                  ? null
                  : () async {
                      await state.load();
                      if (state.error == null && state.data != null) {
                        mutation.stateReread();
                      }
                    },
              child: const Text('إعادة قراءة حالة المستخدم'),
            ),
            if (mutation.error != null) ErrorNotice(mutation.error!),
            if (mutation.busy) const LinearProgressIndicator(),
            if (mutation.uncertain && mutation.reread)
              OutlinedButton(
                onPressed: () async {
                  if (await confirmAction(
                    context,
                    'نتيجة غير مؤكدة',
                    'قرأت الحالة الحالية وأفهم أن العملية السابقة ربما نُفذت. سأختار إجراءً جديداً بنفسي.',
                  )) {
                    mutation.acknowledge();
                  }
                },
                child: const Text('الإقرار قبل أي إجراء جديد'),
              ),
          ],
        ),
        content: (u) => Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(u.fullName, style: Theme.of(context).textTheme.headlineSmall),
            SalesIdentifier(u.username),
            Text(
              'المعرّف ${u.id} • ${u.roleName} • ${u.active ? 'فعال' : 'معطّل'}',
            ),
            if (u.phone != null) SalesIdentifier(u.phone!),
            if (u.email != null) SalesIdentifier(u.email!),
            Text('الإنشاء ${isolateDate(u.createdAt)}'),
            if (u.lastLoginAt != null)
              Text('آخر دخول ${isolateDate(u.lastLoginAt!)}'),
            Text(
              u.mustChange
                  ? 'يجب تغيير كلمة المرور عند الدخول.'
                  : 'لا يوجد إلزام حالي بتغيير كلمة المرور.',
            ),
            const Text(
              'التعديل والتعطيل وإعادة التعيين تلغي الاعتماد على الجلسات القديمة. الخادم يحمي آخر مدير فعال.',
            ),
            if (state.access.edit)
              OutlinedButton(
                onPressed: mutation.busy || mutation.uncertain
                    ? null
                    : () => _editor(u),
                child: const Text('تعديل البيانات'),
              ),
            if (state.access.edit &&
                (u.id != widget.auth.user?.id || !u.active))
              OutlinedButton(
                onPressed: mutation.busy || mutation.uncertain
                    ? null
                    : () => _active(u),
                child: Text(u.active ? 'تعطيل المستخدم' : 'تفعيل المستخدم'),
              ),
            if (state.access.reset && u.id != widget.auth.user?.id)
              OutlinedButton(
                onPressed: mutation.busy || mutation.uncertain
                    ? null
                    : () => _editor(u, reset: true),
                child: const Text('إعادة تعيين كلمة المرور'),
              ),
            if (u.id == widget.auth.user?.id)
              const Text(
                'تغيير كلمة مرورك يتم من الملف الشخصي، وليس إعادة التعيين هنا.',
              ),
          ],
        ),
      ),
    ),
  );
}
