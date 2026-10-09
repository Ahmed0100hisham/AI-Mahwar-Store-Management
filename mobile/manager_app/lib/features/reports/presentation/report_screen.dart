import 'package:flutter/material.dart';

import '../../../core/errors/app_failure.dart';
import '../../../core/utils/money.dart';
import '../../auth/state/auth_controller.dart';
import '../../dashboard/data/dashboard_models.dart';
import '../../final_features/data/feature_access.dart';
import '../../final_features/presentation/feature_widgets.dart';
import '../../final_features/state/feature_state.dart';
import '../../inventory/data/inventory_models.dart';
import '../../inventory/presentation/inventory_screen.dart';
import '../../inventory/presentation/inventory_widgets.dart'
    show InventoryValue;
import '../../parties/data/party_access.dart';
import '../../parties/presentation/party_screen.dart';
import '../../parties/presentation/party_widgets.dart'
    show PartyMoney, PartyBlocked;
import '../../sales/presentation/sales_screen.dart';
import '../../sales/presentation/sales_widgets.dart'
    show SalesIdentifier, isolateDate;
import '../data/report_repository.dart';

class ReportScreen extends StatelessWidget {
  const ReportScreen({super.key, required this.auth});
  final AuthController auth;
  void _open(BuildContext context, String title, Widget screen) =>
      Navigator.push(
        context,
        MaterialPageRoute<void>(
          builder: (_) => Scaffold(
            appBar: AppBar(title: Text(title)),
            body: screen,
          ),
        ),
      );
  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: auth,
    builder: (context, _) {
      final access = FeatureAccess(auth.user),
          analytics = FeatureAccess(auth.user).analytics;
      if (!access.reports || auth.status != AuthStatus.authenticated) {
        return const PartyBlocked();
      }
      final destinations = <(String, String, Widget)>[
        if (analytics!.sales)
          (
            'المبيعات والاتجاهات',
            'الفترات والإجمالي والمرتجعات والمنتجات الأعلى مبيعاً',
            SalesScreen(auth: auth),
          ),
        if (analytics.expenses)
          (
            'المصروفات',
            'الإجمالي والتصنيفات خلال الفترة',
            ReportReadScreen(auth: auth, kind: ReportKind.expenses),
          ),
        if (analytics.cash)
          (
            'دفتر النقدية',
            'الافتتاحي والوارد والصادر والختامي؛ جميع طرق الدفع',
            ReportReadScreen(auth: auth, kind: ReportKind.cash),
          ),
        if (analytics.dashboard)
          (
            'الملخص اليومي',
            'تاريخ أعمال محدد وأقسام مرخّصة مستقلة',
            ReportReadScreen(auth: auth, kind: ReportKind.daily),
          ),
        if (analytics.inventory) ...[
          (
            'ملخص المخزون الحالي',
            'أعداد المنتجات والقيمة عند السماح؛ ليس رصيداً تاريخياً',
            ReportReadScreen(auth: auth, kind: ReportKind.inventory),
          ),
          (
            'المخزون المنخفض',
            'قائمة المخزون الحالي القابلة للبحث والترتيب',
            InventoryScreen(auth: auth, lowStock: true),
          ),
          (
            'المنتجات بطيئة البيع',
            'فترة عدم البيع والصفحات والوحدات المستقلة',
            SlowReportScreen(auth: auth),
          ),
        ],
        for (final kind in PartyKind.values)
          if (PartyAccess(auth.user, kind).financial)
            (
              kind.debtTitle,
              'إجمالي جميع النتائج المطابقة من الخادم، وليس مجموع الصفحة',
              PartyScreen(auth: auth, kind: kind, outstanding: true),
            ),
      ];
      return ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Text('التقارير', style: Theme.of(context).textTheme.headlineSmall),
          const Text(
            'فحص تفصيلي للبيانات المصرّح بها؛ الحسابات والأرقام من الخادم.',
          ),
          for (final d in destinations)
            Card(
              child: ListTile(
                title: Text(d.$1),
                subtitle: Text(d.$2),
                trailing: const Icon(Icons.chevron_left),
                onTap: () => _open(context, d.$1, d.$3),
              ),
            ),
        ],
      );
    },
  );
}

enum ReportKind { expenses, cash, daily, inventory }

class ReportReadScreen extends StatefulWidget {
  const ReportReadScreen({super.key, required this.auth, required this.kind});
  final AuthController auth;
  final ReportKind kind;
  @override
  State<ReportReadScreen> createState() => _ReportReadScreenState();
}

class _ReportReadScreenState extends State<ReportReadScreen> {
  MovementRange range = const MovementRange.preset(MovementPeriod.month);
  String? date, dateError;
  final dateInput = TextEditingController();
  late final repository = ReportRepository(widget.auth.repository.client);
  late final state = FeatureState<Object>(
    widget.auth,
    permit: (a) => switch (widget.kind) {
      ReportKind.expenses => a.analytics?.expenses ?? false,
      ReportKind.cash => a.analytics?.cash ?? false,
      ReportKind.daily => a.analytics?.dashboard ?? false,
      ReportKind.inventory => a.analytics?.inventory ?? false,
    },
    request: () => switch (widget.kind) {
      ReportKind.expenses => repository.expenses(
        FeatureAccess(widget.auth.user),
        range,
      ),
      ReportKind.cash => repository.cash(
        FeatureAccess(widget.auth.user),
        range,
      ),
      ReportKind.daily => repository.daily(
        FeatureAccess(widget.auth.user),
        date,
      ),
      ReportKind.inventory => repository.inventory(
        FeatureAccess(widget.auth.user),
      ),
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
    dateInput.dispose();
    super.dispose();
  }

  Widget _cash(CashSummary c) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      Text('${isolateDate(c.range.from.iso)} — ${isolateDate(c.range.to.iso)}'),
      const Text('دفتر النقدية عبر جميع طرق الدفع؛ ليس النقد الورقي وحده.'),
      PartyMoney('الرصيد الافتتاحي', c.opening),
      PartyMoney('إجمالي الوارد', c.incoming),
      PartyMoney('إجمالي الصادر', c.outgoing),
      PartyMoney('صافي الحركة', c.movement),
      PartyMoney('الرصيد الختامي', c.closing),
    ],
  );
  Widget _inventory(InventorySummary i) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      Text(
        'المنتجات النشطة ${i.active} • المنخفضة ${i.low} • النافدة ${i.out}',
      ),
      const Text('الوحدات المختلفة لا تُجمع؛ هذه أعداد منتجات.'),
      if (FeatureAccess(widget.auth.user).has('PRODUCT_COST') &&
          i.valueAtCost != null)
        PartyMoney('قيمة المخزون الحالي بالتكلفة', i.valueAtCost!),
    ],
  );
  Widget _daily(DailySummary d) {
    final a = FeatureAccess(widget.auth.user).analytics!;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text('تاريخ التقرير: ${isolateDate(d.date.iso)}'),
        if (a.sales && d.sales != null) ...[
          PartyMoney('إجمالي المبيعات', d.sales!.grossSales),
          PartyMoney('قيمة المرتجعات بتاريخ الإرجاع', d.sales!.returns),
          PartyMoney('صافي المبيعات', d.sales!.netSales),
          Text(
            'الفواتير ${d.sales!.invoiceCount} • المرتجعات ${d.sales!.returnCount}',
          ),
          PartyMoney('متوسط الفاتورة قبل المرتجعات', d.sales!.averageInvoice),
          if (a.profit && d.sales!.netProfit != null)
            PartyMoney(
              'صافي الربح بعد المرتجعات والمصروفات',
              d.sales!.netProfit!,
            ),
        ],
        if (a.expenses && d.expenses != null) ...[
          PartyMoney('المصروفات', d.expenses!.total),
          Text('عمليات المصروفات ${d.expenses!.entries}'),
        ],
        if (a.cash && d.cash != null) _cash(d.cash!),
        if (a.inventory && d.inventory != null) ...[
          Text(
            'لقطة المخزون الحالية بتاريخ ${isolateDate(d.inventoryDate!.iso)}؛ ليست مخزوناً تاريخياً ليوم التقرير.',
          ),
          _inventory(d.inventory!),
        ],
        if (d.sales == null &&
            d.expenses == null &&
            d.cash == null &&
            d.inventory == null)
          const Text('لا توجد أقسام متاحة لهذا الحساب في الاستجابة.'),
      ],
    );
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: state,
    builder: (context, _) => FeatureBody<Object>(
      state: state,
      header: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (widget.kind == ReportKind.daily) ...[
            TextField(
              controller: dateInput,
              textDirection: TextDirection.ltr,
              decoration: const InputDecoration(
                labelText: 'تاريخ التقرير (yyyy-MM-dd)؛ فارغ ليوم الخادم',
              ),
            ),
            OutlinedButton(
              onPressed: () {
                try {
                  final text = dateInput.text.trim();
                  if (text.isNotEmpty) BusinessDay.parse(text);
                  setState(() {
                    date = text.isEmpty ? null : text;
                    dateError = null;
                  });
                  state.invalidate();
                  state.load();
                } on AppFailure {
                  setState(() => dateError = 'أدخل تاريخ أعمال صحيحاً.');
                }
              },
              child: const Text('عرض اليوم'),
            ),
            if (dateError != null) Text(dateError!),
          ] else if (widget.kind != ReportKind.inventory)
            FeatureRangeControls(
              range: range,
              changed: (v) {
                range = v;
                state.invalidate();
                state.load();
              },
              refresh: state.load,
              loading: state.loading,
            )
          else
            OutlinedButton(
              onPressed: state.loading ? null : state.load,
              child: const Text('تحديث المخزون الحالي'),
            ),
        ],
      ),
      content: (data) => switch (data) {
        ExpenseReport r => Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
              '${isolateDate(r.summary.range.from.iso)} — ${isolateDate(r.summary.range.to.iso)}',
            ),
            PartyMoney('إجمالي المصروفات', r.summary.total),
            Text('العمليات ${r.summary.entries}'),
            if (r.categories.isEmpty) const Text('لا توجد مصروفات في الفترة.'),
            for (final c in r.categories)
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Text(c.name),
                      PartyMoney('المبلغ', c.amount),
                      Text('العمليات ${c.entries}'),
                    ],
                  ),
                ),
              ),
          ],
        ),
        CashSummary c => _cash(c),
        DailySummary d => _daily(d),
        InventorySummary i => _inventory(i),
        _ => const SizedBox.shrink(),
      },
    ),
  );
}

class SlowReportScreen extends StatefulWidget {
  const SlowReportScreen({super.key, required this.auth});
  final AuthController auth;
  @override
  State<SlowReportScreen> createState() => _SlowReportScreenState();
}

class _SlowReportScreenState extends State<SlowReportScreen> {
  int days = 30;
  String search = '', daysError = '';
  final daysInput = TextEditingController(text: '30');
  late final repository = ReportRepository(widget.auth.repository.client);
  late final state = FeaturePaged<SlowProduct>(
    widget.auth,
    permit: (a) => a.analytics?.inventory ?? false,
    fetch: (page, size) => repository.slow(
      FeatureAccess(widget.auth.user),
      days,
      search,
      page,
      size,
    ),
    identity: (p) => p.code,
  );
  @override
  void initState() {
    super.initState();
    state.load();
  }

  @override
  void dispose() {
    state.dispose();
    daysInput.dispose();
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
          const Text('المنتجات بطيئة البيع؛ ترتيب آخر بيع تصاعدياً'),
          TextField(
            maxLength: 100,
            decoration: const InputDecoration(
              labelText: 'البحث بالاسم أو الكود',
              counterText: '',
            ),
            onChanged: (v) {
              search = v;
              state.queryChanged(debounce: true);
            },
          ),
          TextField(
            controller: daysInput,
            keyboardType: TextInputType.number,
            decoration: const InputDecoration(
              labelText: 'أيام عدم البيع (1–3650)',
            ),
            onSubmitted: (v) {
              final next = int.tryParse(v.trim());
              if (next == null || next < 1 || next > 3650) {
                setState(() => daysError = 'أدخل عدد أيام بين 1 و3650.');
                return;
              }
              setState(() {
                days = next;
                daysError = '';
              });
              state.queryChanged();
            },
          ),
          if (daysError.isNotEmpty) Text(daysError),
          Text('الفترة المطبّقة: $days يوماً'),
          OutlinedButton(
            onPressed: state.loading ? null : state.load,
            child: const Text('تحديث البيانات'),
          ),
        ],
      ),
      row: (SlowProduct p) => Card(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(p.name),
              SalesIdentifier(p.code),
              InventoryValue('${formatQuantity(p.quantity)} ${p.unit}'),
              Text(
                p.lastSale == null
                    ? 'لا يوجد بيع سابق مسجّل'
                    : 'آخر بيع ${isolateDate(p.lastSale!.iso)} • ${p.daysSinceLastSale} يوماً',
              ),
              if (FeatureAccess(widget.auth.user).has('PRODUCT_COST') &&
                  p.valueAtCost != null)
                PartyMoney('قيمة المخزون الحالي بالتكلفة', p.valueAtCost!),
            ],
          ),
        ),
      ),
    ),
  );
}
