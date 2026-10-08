import 'package:flutter/material.dart';

import '../../../shared/widgets/states.dart';
import '../../auth/state/auth_controller.dart';
import '../../profile/presentation/profile_screen.dart';
import 'dashboard_screen.dart';

class ManagerShell extends StatefulWidget {
  const ManagerShell({super.key, required this.auth});
  final AuthController auth;
  @override
  State<ManagerShell> createState() => _ManagerShellState();
}

class _ManagerShellState extends State<ManagerShell> {
  bool _profile = false;
  AuthController get auth => widget.auth;
  @override
  Widget build(BuildContext context) {
    final user = auth.user!;
    final dashboard = user.allows('DASHBOARD');
    final profile = _profile || !dashboard;
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
              if (dashboard)
                ListTile(
                  selected: !profile,
                  leading: const Icon(Icons.dashboard_outlined),
                  title: const Text('لوحة المتابعة'),
                  onTap: () {
                    Navigator.pop(context);
                    setState(() => _profile = false);
                  },
                ),
              ListTile(
                selected: profile,
                leading: const Icon(Icons.person_outline),
                title: const Text('الملف الشخصي والأجهزة'),
                onTap: () {
                  Navigator.pop(context);
                  setState(() => _profile = true);
                },
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
          Expanded(
            child: profile
                ? ProfileScreen(auth: auth)
                : DashboardScreen(auth: auth),
          ),
        ],
      ),
    );
  }
}
