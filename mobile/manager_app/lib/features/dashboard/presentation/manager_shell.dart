import 'package:flutter/material.dart';

import '../../../shared/widgets/states.dart';
import '../../auth/state/auth_controller.dart';
import '../../profile/presentation/profile_screen.dart';
import '../../inventory/data/inventory_access.dart';
import '../../inventory/presentation/inventory_screen.dart';
import 'dashboard_screen.dart';

class ManagerShell extends StatefulWidget {
  const ManagerShell({super.key, required this.auth});
  final AuthController auth;
  @override
  State<ManagerShell> createState() => _ManagerShellState();
}

enum _Destination { dashboard, inventory, profile }

class _ManagerShellState extends State<ManagerShell> {
  _Destination _destination = _Destination.dashboard;
  bool _lowStock = false;
  AuthController get auth => widget.auth;
  @override
  Widget build(BuildContext context) {
    final user = auth.user!;
    final dashboard = user.allows('DASHBOARD');
    final inventory = InventoryAccess(user).enter;
    final selected = switch (_destination) {
      _Destination.dashboard when dashboard => _Destination.dashboard,
      _Destination.inventory when inventory => _Destination.inventory,
      _Destination.profile => _Destination.profile,
      _ =>
        dashboard
            ? _Destination.dashboard
            : inventory
            ? _Destination.inventory
            : _Destination.profile,
    };
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
                  selected: selected == _Destination.dashboard,
                  leading: const Icon(Icons.dashboard_outlined),
                  title: const Text('لوحة المتابعة'),
                  onTap: () {
                    Navigator.pop(context);
                    setState(() => _destination = _Destination.dashboard);
                  },
                ),
              if (inventory)
                ListTile(
                  selected: selected == _Destination.inventory,
                  leading: const Icon(Icons.inventory_2_outlined),
                  title: const Text('المنتجات والمخزون'),
                  onTap: () {
                    Navigator.pop(context);
                    setState(() {
                      _destination = _Destination.inventory;
                      _lowStock = false;
                    });
                  },
                ),
              ListTile(
                selected: selected == _Destination.profile,
                leading: const Icon(Icons.person_outline),
                title: const Text('الملف الشخصي والأجهزة'),
                onTap: () {
                  Navigator.pop(context);
                  setState(() => _destination = _Destination.profile);
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
            child: switch (selected) {
              _Destination.profile => ProfileScreen(auth: auth),
              _Destination.inventory => InventoryScreen(
                key: ValueKey(_lowStock),
                auth: auth,
                lowStock: _lowStock,
              ),
              _Destination.dashboard => DashboardScreen(
                auth: auth,
                onInventory: (low) {
                  setState(() {
                    _destination = _Destination.inventory;
                    _lowStock = low;
                  });
                },
              ),
            },
          ),
        ],
      ),
    );
  }
}
