import 'package:flutter/material.dart';

import '../../../core/routing/manager_destination.dart';
import '../../../shared/widgets/states.dart';
import '../../auth/state/auth_controller.dart';
import '../../profile/presentation/profile_screen.dart';

class ManagerShell extends StatelessWidget {
  const ManagerShell({super.key, required this.auth});
  final AuthController auth;
  @override
  Widget build(BuildContext context) {
    final user = auth.user!;
    final available = ManagerDestination.future
        .where((d) => d.visibleTo(user))
        .toList();
    return Scaffold(
      appBar: AppBar(
        title: const Text('مساحة الإدارة'),
        actions: [
          IconButton(
            onPressed: auth.busy ? null : () => auth.checkSession(),
            tooltip: 'تحديث الحساب والصلاحيات',
            icon: const Icon(Icons.sync),
          ),
          IconButton(
            onPressed: auth.busy
                ? null
                : () async {
                    if (await confirmAction(
                      context,
                      'تسجيل الخروج',
                      'هل تريد إنهاء جلسة هذا الجهاز؟',
                    )) {
                      await auth.logout();
                    }
                  },
            tooltip: 'تسجيل الخروج',
            icon: const Icon(Icons.logout),
          ),
        ],
      ),
      drawer: Drawer(
        child: SafeArea(
          child: ListView(
            children: [
              Padding(
                padding: const EdgeInsets.all(24),
                child: Text(
                  user.fullName,
                  style: Theme.of(context).textTheme.titleLarge,
                ),
              ),
              const ListTile(
                selected: true,
                leading: Icon(Icons.person_outline),
                title: Text('الملف الشخصي والأجهزة'),
              ),
              const Divider(),
              const Padding(
                padding: EdgeInsets.all(16),
                child: Text('الأقسام القادمة حسب صلاحياتك'),
              ),
              ...available.map(
                (d) => ListTile(
                  enabled: false,
                  title: Text(d.label),
                  trailing: const Icon(Icons.lock_clock_outlined),
                ),
              ),
              const Padding(
                padding: EdgeInsets.all(16),
                child: Text(
                  'هذه المرحلة لإدارة الدخول والجلسات. ستُضاف وحدات المتابعة لاحقاً.',
                ),
              ),
            ],
          ),
        ),
      ),
      body: Column(
        children: [
          if (auth.error != null)
            Padding(
              padding: const EdgeInsets.all(16),
              child: ErrorNotice(
                auth.error!,
                onRetry: () => auth.checkSession(),
              ),
            ),
          Expanded(child: ProfileScreen(auth: auth)),
        ],
      ),
    );
  }
}
