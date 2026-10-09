import 'package:flutter/material.dart';

import '../../parties/data/party_access.dart';

import '../../../core/utils/money.dart';
import '../../../shared/widgets/states.dart';
import '../../auth/state/auth_controller.dart';
import '../data/dashboard_access.dart';
import '../data/dashboard_models.dart';
import '../data/dashboard_repository.dart';
import '../state/dashboard_controller.dart';

class DashboardScreen extends StatefulWidget {
  const DashboardScreen({
    super.key,
    required this.auth,
    this.onInventory,
    this.onSales,
    this.onParty,
  });
  final AuthController auth;
  final ValueChanged<bool>? onInventory;
  final ValueChanged<bool>? onSales;
  final ValueChanged<PartyKind>? onParty;
  @override
  State<DashboardScreen> createState() => _DashboardScreenState();
}

class _DashboardScreenState extends State<DashboardScreen> {
  late final DashboardController controller;
  @override
  void initState() {
    super.initState();
    controller = DashboardController(
      widget.auth,
      DashboardRepository(widget.auth.repository.client),
    );
    controller.load();
  }

  @override
  void dispose() {
    controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: controller,
    builder: (context, _) {
      final user = widget.auth.user;
      if (user == null || !controller.allowed) return const SizedBox.shrink();
      final access = DashboardAccess(user);
      final data = controller.data;
      return RefreshIndicator(
        onRefresh: controller.load,
        child: SingleChildScrollView(
          key: const PageStorageKey('manager-dashboard'),
          physics: const AlwaysScrollableScrollPhysics(),
          padding: const EdgeInsets.all(16),
          child: Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 1120),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text(
                    'لوحة المتابعة',
                    style: Theme.of(context).textTheme.headlineSmall,
                  ),
                  const SizedBox(height: 4),
                  Text(
                    'مرحباً، ${user.fullName}',
                    style: Theme.of(context).textTheme.bodyLarge,
                  ),
                  const SizedBox(height: 16),
                  Wrap(
                    spacing: 16,
                    runSpacing: 12,
                    crossAxisAlignment: WrapCrossAlignment.center,
                    children: [
                      if (access.periods)
                        SizedBox(
                          width: 210,
                          child: DropdownButtonFormField<DashboardPeriod>(
                            key: ValueKey(controller.period),
                            initialValue: controller.period,
                            isExpanded: true,
                            decoration: const InputDecoration(
                              labelText: 'فترة المتابعة',
                              contentPadding: EdgeInsets.symmetric(
                                horizontal: 12,
                                vertical: 12,
                              ),
                            ),
                            items: DashboardPeriod.values
                                .map(
                                  (p) => DropdownMenuItem(
                                    value: p,
                                    child: Text(p.label),
                                  ),
                                )
                                .toList(),
                            onChanged: (p) {
                              if (p != null) controller.selectPeriod(p);
                            },
                          ),
                        ),
                      OutlinedButton.icon(
                        onPressed: controller.loading ? null : controller.load,
                        icon: const Icon(Icons.refresh, size: 20),
                        label: const Text('تحديث البيانات'),
                      ),
                    ],
                  ),
                  if (data != null) ...[
                    const SizedBox(height: 12),
                    Text(
                      'تاريخ الأعمال: ${_date(data.overview.businessDate.iso)} • توقيت الكويت',
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                    Text(
                      'الفترة: ${_date(data.range.from.iso)} — ${_date(data.range.to.iso)}',
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                  ],
                  const SizedBox(height: 16),
                  if (controller.loading && data == null)
                    const Padding(
                      padding: EdgeInsets.symmetric(vertical: 48),
                      child: BusyView(label: 'جارٍ تحميل لوحة المتابعة…'),
                    ),
                  if (controller.loading && data != null)
                    const LinearProgressIndicator(
                      semanticsLabel: 'جارٍ تحديث بيانات لوحة المتابعة',
                    ),
                  if (controller.error != null) ...[
                    ErrorNotice(
                      controller.error!,
                      onRetry: controller.loading ? null : controller.load,
                    ),
                    if (data != null)
                      const Padding(
                        padding: EdgeInsets.only(top: 8),
                        child: Text(
                          'تعذر التحديث. البيانات المعروضة من آخر تحميل ناجح.',
                        ),
                      ),
                    const SizedBox(height: 16),
                  ],
                  if (data != null)
                    _DashboardContent(
                      data: data,
                      access: access,
                      onInventory: widget.onInventory,
                      onSales: widget.onSales,
                      onParty: widget.onParty,
                    ),
                ],
              ),
            ),
          ),
        ),
      );
    },
  );
}

typedef _Figure = ({String label, String value, bool money});

// Isolate ISO dates from surrounding Arabic bidirectional text.
String _date(String iso) => '\u2066$iso\u2069';

class _DashboardContent extends StatelessWidget {
  const _DashboardContent({
    required this.data,
    required this.access,
    this.onInventory,
    this.onSales,
    this.onParty,
  });
  final DashboardData data;
  final DashboardAccess access;
  final ValueChanged<bool>? onInventory;
  final ValueChanged<bool>? onSales;
  final ValueChanged<PartyKind>? onParty;
  @override
  Widget build(BuildContext context) {
    final metrics = data.overview.metrics;
    OverviewMetric? metric(String key) =>
        access.metric(key) ? metrics[key] : null;
    final primary = <_Figure>[
      if (access.sales && data.sales != null)
        (
          label: 'صافي المبيعات • ${data.period.label}',
          value: data.sales!.netSales,
          money: true,
        ),
      if (access.sales && access.profit && data.sales?.netProfit != null)
        (
          label: 'صافي الربح • ${data.period.label}',
          value: data.sales!.netProfit!,
          money: true,
        ),
      if (access.expenses && data.expenses != null)
        (
          label: 'المصروفات • ${data.period.label}',
          value: data.expenses!.total,
          money: true,
        ),
      if (access.sales && data.sales != null)
        (
          label: 'الفواتير • ${data.period.label}',
          value: '${data.sales!.invoiceCount}',
          money: false,
        ),
      if (data.period == DashboardPeriod.today &&
          data.sales == null &&
          metric('TODAY_SALES') != null)
        (
          label: 'صافي مبيعات اليوم',
          value: metric('TODAY_SALES')!.value,
          money: true,
        ),
      if (data.period == DashboardPeriod.today &&
          data.sales == null &&
          metric('TODAY_INVOICES') != null)
        (
          label: 'فواتير اليوم',
          value: metric('TODAY_INVOICES')!.value,
          money: false,
        ),
    ];
    final monthly = <_Figure>[
      if (metric('MONTH_SALES') != null)
        (
          label: 'صافي مبيعات الشهر',
          value: metric('MONTH_SALES')!.value,
          money: true,
        ),
      if (metric('NET_PROFIT') != null)
        (
          label: 'صافي ربح الشهر',
          value: metric('NET_PROFIT')!.value,
          money: true,
        ),
      if (metric('EXPENSES') != null)
        (label: 'مصروفات الشهر', value: metric('EXPENSES')!.value, money: true),
    ];
    final operational = <_Figure>[
      if (metric('CASH_BALANCE') != null)
        (
          label: 'رصيد دفتر النقدية الحالي',
          value: metric('CASH_BALANCE')!.value,
          money: true,
        ),
      if (metric('RECEIVABLES') != null)
        (
          label: 'ديون العملاء الحالية',
          value: metric('RECEIVABLES')!.value,
          money: true,
        ),
      if (metric('PAYABLES') != null)
        (
          label: 'مستحقات الموردين الحالية',
          value: metric('PAYABLES')!.value,
          money: true,
        ),
      if (metric('LOW_STOCK') != null && data.daily.inventory == null)
        (
          label: 'منتجات عند حد النقص',
          value: metric('LOW_STOCK')!.value,
          money: false,
        ),
    ];
    final inventory = access.inventory ? data.daily.inventory : null;
    final daily = data.daily;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (primary.isNotEmpty) _Figures(figures: primary),
        if (onParty != null)
          Wrap(
            spacing: 16,
            runSpacing: 8,
            children: [
              if (metric('RECEIVABLES') != null &&
                  access.has('CUSTOMERS_VIEW') &&
                  access.has('CUSTOMER_BALANCE_VIEW'))
                TextButton(
                  onPressed: () => onParty!(PartyKind.customer),
                  child: const Text('عرض ديون العملاء'),
                ),
              if (metric('PAYABLES') != null &&
                  access.has('SUPPLIERS_VIEW') &&
                  access.has('SUPPLIER_BALANCE_VIEW'))
                TextButton(
                  onPressed: () => onParty!(PartyKind.supplier),
                  child: const Text('عرض مستحقات الموردين'),
                ),
            ],
          ),
        if (onSales != null && (access.sales || access.has('SALES_VIEW')))
          Wrap(
            spacing: 16,
            runSpacing: 8,
            children: [
              if (access.sales)
                TextButton(
                  onPressed: () => onSales!(false),
                  child: const Text('عرض ملخص المبيعات'),
                ),
              if (access.has('SALES_VIEW'))
                TextButton(
                  onPressed: () => onSales!(true),
                  child: const Text('عرض الفواتير'),
                ),
            ],
          ),
        if (primary.isEmpty &&
            monthly.isEmpty &&
            operational.isEmpty &&
            inventory == null &&
            daily.sales == null &&
            daily.expenses == null &&
            daily.cash == null)
          const _Section(
            title: 'نظرة عامة',
            child: Text('لا توجد بيانات متاحة لعرضها ضمن صلاحياتك الحالية.'),
          ),
        if (operational.isNotEmpty) ...[
          _Heading('الوضع الحالي'),
          _Figures(figures: operational),
        ],
        if (inventory != null)
          _Section(
            title: 'المخزون الحالي',
            action: onInventory == null
                ? null
                : TextButton(
                    onPressed: () => onInventory!(false),
                    child: const Text('عرض المنتجات والمخزون'),
                  ),
            subtitle:
                'لقطة بتاريخ ${_date(daily.inventoryDate!.iso)}؛ ليست رصيداً تاريخياً للفترة المختارة.',
            child: _Figures(
              figures: [
                (
                  label: 'أصناف نشطة',
                  value: '${inventory.active}',
                  money: false,
                ),
                (
                  label: 'أصناف عند حد النقص',
                  value: '${inventory.low}',
                  money: false,
                ),
                (label: 'أصناف نافدة', value: '${inventory.out}', money: false),
                if (access.cost && inventory.valueAtCost != null)
                  (
                    label: 'قيمة المخزون بالتكلفة',
                    value: inventory.valueAtCost!,
                    money: true,
                  ),
              ],
            ),
          ),
        if (monthly.isNotEmpty) ...[
          _Heading('مؤشرات الشهر الحالي'),
          const Text('قيم شهرية مستقلة عن اختيار فترة المتابعة.'),
          const SizedBox(height: 12),
          _Figures(figures: monthly),
        ],
        if (access.cash && data.cash != null)
          _Section(
            title: 'دفتر النقدية • ${data.period.label}',
            subtitle: 'يشمل جميع طرق الدفع.',
            child: _Facts(
              items: [
                ('الرصيد الافتتاحي', formatKwd(data.cash!.opening)),
                ('الوارد', formatKwd(data.cash!.incoming)),
                ('الصادر', formatKwd(data.cash!.outgoing)),
                ('صافي الحركة', formatKwd(data.cash!.movement)),
                ('الرصيد الختامي', formatKwd(data.cash!.closing)),
              ],
            ),
          ),
        if ((access.sales && daily.sales != null) ||
            (access.expenses && daily.expenses != null) ||
            (access.cash && daily.cash != null))
          _Section(
            title: 'ملخص اليوم',
            subtitle:
                'يوم الأعمال ${_date(daily.date.iso)}؛ يظل يومياً عند تغيير الفترة.',
            child: _Facts(
              items: [
                if (access.sales && daily.sales != null) ...[
                  ('مبيعات قبل المرتجعات', formatKwd(daily.sales!.grossSales)),
                  ('قيمة المرتجعات', formatKwd(daily.sales!.returns)),
                  ('عدد المرتجعات', '${daily.sales!.returnCount}'),
                  (
                    'متوسط الفاتورة قبل المرتجعات',
                    formatKwd(daily.sales!.averageInvoice),
                  ),
                ],
                if (access.expenses && daily.expenses != null)
                  ('قيود المصروفات', '${daily.expenses!.entries}'),
                if (access.cash && daily.cash != null)
                  ('حركة النقدية اليوم', formatKwd(daily.cash!.movement)),
              ],
            ),
          ),
        if (access.sales && data.top != null)
          _Section(
            title: 'الأكثر مبيعاً • ${data.period.label}',
            subtitle: 'حتى 5 أصناف، مرتبة حسب صافي الكمية بعد المرتجعات.',
            child: data.top!.isEmpty
                ? const Text('لا توجد مبيعات أصناف خلال هذه الفترة.')
                : Column(
                    children: data.top!
                        .map(
                          (p) => _ProductRow(
                            name: p.name,
                            code: p.code,
                            details: [
                              (
                                'صافي الكمية',
                                '${formatQuantity(p.quantity)} ${p.unit}',
                              ),
                              ('صافي الإيراد', formatKwd(p.revenue)),
                              if (access.profit && p.profit != null)
                                ('الربح الإجمالي', formatKwd(p.profit!)),
                            ],
                          ),
                        )
                        .toList(),
                  ),
          ),
        if (access.sales && data.trend != null)
          _Section(
            title: 'حركة المبيعات اليومية',
            subtitle: 'آخر 7 أيام حتى تاريخ الأعمال ضمن الفترة؛ القيم والمرتجعات من الخادم.',
            child: Column(
              children: data.trend!
                  .where(
                    (p) => p.day.compareTo(data.overview.businessDate) <= 0,
                  )
                  .toList()
                  .reversed
                  .take(7)
                  .toList()
                  .reversed
                  .map(
                    (p) => _ProductRow(
                      name: _date(p.day.iso),
                      details: [
                        ('صافي المبيعات', formatKwd(p.netSales)),
                        ('الفواتير', '${p.invoices}'),
                      ],
                    ),
                  )
                  .toList(),
            ),
          ),
        if (access.inventory && data.lowStock != null)
          _Section(
            title: 'تنبيهات نقص المخزون',
            action: onInventory == null
                ? null
                : TextButton(
                    onPressed: () => onInventory!(true),
                    child: const Text('عرض المخزون المنخفض'),
                  ),
            subtitle:
                'عرض ${data.lowStock!.items.length} من ${data.lowStock!.total} صنفاً؛ الوضع الحالي.',
            child: data.lowStock!.items.isEmpty
                ? const Text('لا توجد أصناف عند حد النقص حالياً.')
                : Column(
                    children: data.lowStock!.items
                        .map(
                          (p) => _ProductRow(
                            name: p.name,
                            code: p.code,
                            details: [
                              (
                                'المتاح',
                                '${formatQuantity(p.quantity)} ${p.unit}',
                              ),
                              (
                                'الحد الأدنى',
                                '${formatQuantity(p.minimum)} ${p.unit}',
                              ),
                            ],
                          ),
                        )
                        .toList(),
                  ),
          ),
        if (access.inventory && data.slow != null)
          _Section(
            title: 'أصناف بطيئة الحركة',
            subtitle:
                'لم تُبع خلال آخر 30 يوماً أو لم تُبع سابقاً. عرض ${data.slow!.items.length} من ${data.slow!.total}.',
            child: data.slow!.items.isEmpty
                ? const Text('لا توجد أصناف تطابق معيار بطء الحركة.')
                : Column(
                    children: data.slow!.items
                        .map(
                          (p) => _ProductRow(
                            name: p.name,
                            code: p.code,
                            details: [
                              (
                                'المتاح',
                                '${formatQuantity(p.quantity)} ${p.unit}',
                              ),
                              (
                                'آخر بيع',
                                p.lastSale == null
                                    ? 'لم يُبع سابقاً'
                                    : _date(p.lastSale!.iso),
                              ),
                              if (access.cost && p.valueAtCost != null)
                                ('القيمة بالتكلفة', formatKwd(p.valueAtCost!)),
                            ],
                          ),
                        )
                        .toList(),
                  ),
          ),
        const SizedBox(height: 24),
        Text(
          'بيانات للمتابعة فقط. جميع القيم والحسابات والصلاحيات مصدرها الخدمة.',
          style: Theme.of(context).textTheme.bodySmall,
        ),
      ],
    );
  }
}

class _Heading extends StatelessWidget {
  const _Heading(this.text);
  final String text;
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(top: 24, bottom: 12),
    child: Text(text, style: Theme.of(context).textTheme.titleMedium),
  );
}

class _Figures extends StatelessWidget {
  const _Figures({required this.figures});
  final List<_Figure> figures;
  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, constraints) {
      final scale = MediaQuery.textScalerOf(context).scale(14) / 14;
      final columns = (constraints.maxWidth / (260 * scale)).floor().clamp(
        1,
        3,
      );
      final width = (constraints.maxWidth - 12 * (columns - 1)) / columns;
      return Wrap(
        spacing: 12,
        runSpacing: 12,
        children: figures
            .map(
              (f) => SizedBox(
                width: width,
                child: Container(
                  padding: const EdgeInsets.all(16),
                  decoration: _surface(context),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        f.label,
                        style: Theme.of(context).textTheme.bodyMedium,
                      ),
                      const SizedBox(height: 12),
                      _ExactValue(
                        f.money ? formatKwd(f.value) : f.value,
                        style: Theme.of(context).textTheme.titleLarge?.copyWith(
                          fontFeatures: const [FontFeature.tabularFigures()],
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            )
            .toList(),
      );
    },
  );
}

BoxDecoration _surface(BuildContext context) => BoxDecoration(
  color: Theme.of(context).colorScheme.surface,
  border: Border.all(color: Theme.of(context).colorScheme.outlineVariant),
  borderRadius: BorderRadius.circular(10),
);

class _Section extends StatelessWidget {
  const _Section({
    required this.title,
    required this.child,
    this.subtitle,
    this.action,
  });
  final String title;
  final String? subtitle;
  final Widget child;
  final Widget? action;
  @override
  Widget build(BuildContext context) => Container(
    margin: const EdgeInsets.only(top: 20),
    padding: const EdgeInsets.all(16),
    decoration: _surface(context),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(title, style: Theme.of(context).textTheme.titleMedium),
        if (subtitle != null) ...[
          const SizedBox(height: 4),
          Text(subtitle!, style: Theme.of(context).textTheme.bodySmall),
        ],
        const SizedBox(height: 16),
        child,
        if (action != null)
          Padding(
            padding: const EdgeInsets.only(top: 12),
            child: Align(
              alignment: AlignmentDirectional.centerStart,
              child: action,
            ),
          ),
      ],
    ),
  );
}

class _Facts extends StatelessWidget {
  const _Facts({required this.items});
  final List<(String, String)> items;
  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: items
        .map(
          (item) => Padding(
            padding: const EdgeInsets.symmetric(vertical: 5),
            child: Wrap(
              alignment: WrapAlignment.spaceBetween,
              spacing: 16,
              runSpacing: 4,
              children: [
                Text(item.$1),
                _ExactValue(
                  item.$2,
                  style: const TextStyle(
                    fontFeatures: [FontFeature.tabularFigures()],
                  ),
                ),
              ],
            ),
          ),
        )
        .toList(),
  );
}

/// Keep the complete decimal readable on one line, without rounding or clipping.
class _ExactValue extends StatelessWidget {
  const _ExactValue(this.value, {this.style});
  final String value;
  final TextStyle? style;
  @override
  Widget build(BuildContext context) => FittedBox(
    fit: BoxFit.scaleDown,
    alignment: AlignmentDirectional.centerStart,
    child: Text(
      value,
      textDirection: TextDirection.ltr,
      maxLines: 1,
      softWrap: false,
      style: style,
    ),
  );
}

class _ProductRow extends StatelessWidget {
  const _ProductRow({required this.name, required this.details, this.code});
  final String name;
  final String? code;
  final List<(String, String)> details;
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 16),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(name, style: Theme.of(context).textTheme.titleSmall),
        if (code != null)
          Text(code!, style: Theme.of(context).textTheme.bodySmall),
        _Facts(items: details),
      ],
    ),
  );
}
