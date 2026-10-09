import 'package:flutter/material.dart';

import '../../../shared/widgets/states.dart';
import '../../auth/state/auth_controller.dart';
import '../../profile/presentation/profile_screen.dart';
import '../../inventory/data/inventory_access.dart';
import '../../inventory/presentation/inventory_screen.dart';
import '../../sales/data/sales_access.dart';
import '../../sales/presentation/sales_screen.dart';
import '../../parties/data/party_access.dart';
import '../../parties/presentation/party_screen.dart';
import '../../final_features/data/feature_access.dart';
import '../../quotations/presentation/quotation_screen.dart';
import '../../reports/presentation/report_screen.dart';
import '../../audit/presentation/audit_screen.dart';
import '../../administration/presentation/admin_screen.dart';
import 'dashboard_screen.dart';

class ManagerShell extends StatefulWidget {
  const ManagerShell({super.key, required this.auth});
  final AuthController auth;
  @override
  State<ManagerShell> createState() => _ManagerShellState();
}

enum _Destination {
  dashboard,
  inventory,
  sales,
  customers,
  suppliers,
  quotations,
  reports,
  audit,
  users,
  profile,
}

class _ManagerShellState extends State<ManagerShell> {
  _Destination _destination = _Destination.dashboard;
  bool _lowStock = false;
  bool _salesInvoices = false;
  bool _partyOutstanding = false;
  AuthController get auth => widget.auth;
  @override
  Widget build(BuildContext context) {
    final user = auth.user!;
    final dashboard = user.allows('DASHBOARD');
    final inventory = InventoryAccess(user).enter;
    final sales = SalesAccess(user).enter;
    final customers = PartyAccess(user, PartyKind.customer).identity;
    final suppliers = PartyAccess(user, PartyKind.supplier).identity;
    final features = FeatureAccess(user);
    final selected = switch (_destination) {
      _Destination.dashboard when dashboard => _Destination.dashboard,
      _Destination.inventory when inventory => _Destination.inventory,
      _Destination.sales when sales => _Destination.sales,
      _Destination.customers when customers => _Destination.customers,
      _Destination.suppliers when suppliers => _Destination.suppliers,
      _Destination.quotations when features.quotations =>
        _Destination.quotations,
      _Destination.reports when features.reports => _Destination.reports,
      _Destination.audit when features.audit => _Destination.audit,
      _Destination.users when features.users => _Destination.users,
      _Destination.profile => _Destination.profile,
      _ =>
        dashboard
            ? _Destination.dashboard
            : inventory
            ? _Destination.inventory
            : sales
            ? _Destination.sales
            : customers
            ? _Destination.customers
            : suppliers
            ? _Destination.suppliers
            : features.quotations
            ? _Destination.quotations
            : features.reports
            ? _Destination.reports
            : features.audit
            ? _Destination.audit
            : features.users
            ? _Destination.users
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
              if (sales)
                ListTile(
                  selected: selected == _Destination.sales,
                  leading: const Icon(Icons.receipt_long_outlined),
                  title: const Text('المبيعات والفواتير'),
                  onTap: () {
                    Navigator.pop(context);
                    setState(() {
                      _destination = _Destination.sales;
                      _salesInvoices = false;
                    });
                  },
                ),
              for (final kind in PartyKind.values)
                if (PartyAccess(user, kind).identity)
                  ListTile(
                    selected:
                        selected ==
                        (kind == PartyKind.customer
                            ? _Destination.customers
                            : _Destination.suppliers),
                    leading: Icon(
                      kind == PartyKind.customer
                          ? Icons.people_outline
                          : Icons.local_shipping_outlined,
                    ),
                    title: Text(kind.title),
                    onTap: () {
                      Navigator.pop(context);
                      setState(() {
                        _destination = kind == PartyKind.customer
                            ? _Destination.customers
                            : _Destination.suppliers;
                        _partyOutstanding = false;
                      });
                    },
                  ),
              for (final entry in <(_Destination, bool, String, IconData)>[
                (
                  _Destination.quotations,
                  features.quotations,
                  'عروض الأسعار',
                  Icons.description_outlined,
                ),
                (
                  _Destination.reports,
                  features.reports,
                  'التقارير',
                  Icons.analytics_outlined,
                ),
                (
                  _Destination.audit,
                  features.audit,
                  'سجل العمليات',
                  Icons.history,
                ),
                (
                  _Destination.users,
                  features.users,
                  'إدارة المستخدمين',
                  Icons.manage_accounts_outlined,
                ),
              ])
                if (entry.$2)
                  ListTile(
                    selected: selected == entry.$1,
                    leading: Icon(entry.$4),
                    title: Text(entry.$3),
                    onTap: () {
                      Navigator.pop(context);
                      setState(() => _destination = entry.$1);
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
              _Destination.quotations => QuotationScreen(auth: auth),
              _Destination.reports => ReportScreen(auth: auth),
              _Destination.audit => AuditScreen(auth: auth),
              _Destination.users => AdminScreen(auth: auth),
              _Destination.profile => ProfileScreen(auth: auth),
              _Destination.customers || _Destination.suppliers => PartyScreen(
                key: ValueKey((selected, _partyOutstanding)),
                auth: auth,
                kind: selected == _Destination.customers
                    ? PartyKind.customer
                    : PartyKind.supplier,
                outstanding: _partyOutstanding,
              ),
              _Destination.sales => SalesScreen(
                key: ValueKey(_salesInvoices),
                auth: auth,
                invoices: _salesInvoices,
              ),
              _Destination.inventory => InventoryScreen(
                key: ValueKey(_lowStock),
                auth: auth,
                lowStock: _lowStock,
              ),
              _Destination.dashboard => DashboardScreen(
                auth: auth,
                onParty: (kind) {
                  if (!PartyAccess(auth.user, kind).financial) return;
                  setState(() {
                    _destination = kind == PartyKind.customer
                        ? _Destination.customers
                        : _Destination.suppliers;
                    _partyOutstanding = true;
                  });
                },
                onSales: (invoices) {
                  setState(() {
                    _destination = _Destination.sales;
                    _salesInvoices = invoices;
                  });
                },
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
