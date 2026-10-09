import 'package:flutter/material.dart';

import '../../../core/utils/money.dart';
import '../../auth/state/auth_controller.dart';
import '../../inventory/presentation/inventory_widgets.dart';
import '../data/sales_access.dart';
import '../data/sales_repository.dart';
import '../state/sales_controller.dart';
import 'invoice_list.dart';
import 'sales_widgets.dart';

class SalesScreen extends StatefulWidget {
  const SalesScreen({super.key, required this.auth, this.invoices = false});
  final AuthController auth;
  final bool invoices;
  @override
  State<SalesScreen> createState() => _SalesScreenState();
}

class _SalesScreenState extends State<SalesScreen> {
  late bool invoices = widget.invoices;
  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: widget.auth,
    builder: (context, _) {
      final access = SalesAccess(widget.auth.user);
      if (!access.enter) {
        return const InventoryBlocked();
      }
      final selected = access.invoices && (invoices || !access.reports);
      return Column(
        children: [
          if (access.reports && access.invoices)
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 0),
              child: Wrap(
                spacing: 16,
                runSpacing: 4,
                children: [
                  TextButton(
                    onPressed: () => setState(() => invoices = false),
                    child: Text(
                      'ملخص المبيعات',
                      style: TextStyle(
                        fontWeight: !selected
                            ? FontWeight.bold
                            : FontWeight.normal,
                        decoration: !selected ? TextDecoration.underline : null,
                      ),
                    ),
                  ),
                  TextButton(
                    onPressed: () => setState(() => invoices = true),
                    child: Text(
                      'الفواتير',
                      style: TextStyle(
                        fontWeight: selected
                            ? FontWeight.bold
                            : FontWeight.normal,
                        decoration: selected ? TextDecoration.underline : null,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          Expanded(
            child: selected
                ? InvoiceList(auth: widget.auth)
                : _SalesOverview(auth: widget.auth),
          ),
        ],
      );
    },
  );
}

class _SalesOverview extends StatefulWidget {
  const _SalesOverview({required this.auth});
  final AuthController auth;
  @override
  State<_SalesOverview> createState() => _SalesOverviewState();
}

class _SalesOverviewState extends State<_SalesOverview> {
  late final SalesOverviewController state;
  @override
  void initState() {
    super.initState();
    state = SalesOverviewController(
      widget.auth,
      SalesRepository(widget.auth.repository.client),
    );
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
    builder: (context, _) {
      if (!state.allowed) {
        return const InventoryBlocked();
      }
      final data = state.data;
      return RefreshIndicator(
        onRefresh: state.load,
        child: SingleChildScrollView(
          physics: const AlwaysScrollableScrollPhysics(),
          padding: const EdgeInsets.all(16),
          child: Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 1000),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text(
                    'المبيعات',
                    style: Theme.of(context).textTheme.headlineSmall,
                  ),
                  const SizedBox(height: 16),
                  SalesRangeControls(
                    range: state.range,
                    onChanged: state.setRange,
                    onRefresh: state.load,
                    loading: state.loading,
                  ),
                  const SizedBox(height: 16),
                  if (state.loading)
                    const LinearProgressIndicator(
                      semanticsLabel: 'جارٍ تحميل المبيعات',
                    ),
                  if (state.error != null)
                    InventoryError(state.error!, onRetry: state.load),
                  if (data != null) ...[
                    Text(
                      'النتائج المعروضة: ${isolateDate(data.summary.range.from.iso)} — ${isolateDate(data.summary.range.to.iso)}',
                    ),
                    if (state.error != null)
                      const Text('تعذر تطبيق الطلب. هذه نتائج آخر تحميل ناجح.'),
                    SalesSection(
                      title: 'ملخص الفترة',
                      note: 'المبيعات حسب تاريخ الفاتورة، والمرتجعات حسب تاريخ المرتجع حتى لو كانت الفاتورة أقدم. هذه ليست مبالغ التحصيل.',
                      child: InventoryFacts([
                        ('إجمالي المبيعات', formatKwd(data.summary.grossSales)),
                        ('قيمة المرتجعات', formatKwd(data.summary.returns)),
                        ('صافي المبيعات', formatKwd(data.summary.netSales)),
                        ('عدد الفواتير', '${data.summary.invoiceCount}'),
                        ('عدد المرتجعات', '${data.summary.returnCount}'),
                        (
                          'متوسط الفاتورة قبل المرتجعات',
                          formatKwd(data.summary.averageInvoice),
                        ),
                        if (state.access.reportProfit &&
                            data.summary.netProfit != null)
                          (
                            'صافي الربح بعد المصروفات',
                            formatKwd(data.summary.netProfit!),
                          ),
                      ]),
                    ),
                    const Divider(),
                    ExpansionTile(
                      tilePadding: EdgeInsets.zero,
                      title: const Text('اتجاه صافي المبيعات اليومي'),
                      subtitle: const Text(
                        'قيم فعلية من الخادم؛ تشمل الأيام بلا حركة.',
                      ),
                      children: [
                        for (final point in data.trend)
                          Padding(
                            padding: const EdgeInsets.symmetric(vertical: 8),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.stretch,
                              children: [
                                InventoryValue(point.day.iso),
                                InventoryFacts([
                                  ('صافي المبيعات', formatKwd(point.netSales)),
                                  ('عدد الفواتير', '${point.invoices}'),
                                ]),
                                const Divider(),
                              ],
                            ),
                          ),
                      ],
                    ),
                    SalesSection(
                      title: 'الأصناف الأعلى مبيعاً',
                      note: 'أعلى 5 أصناف وفق صافي إيراد الفترة؛ الكميات بعد المرتجعات وبوحدة كل صنف.',
                      child: data.top.isEmpty
                          ? const Text('لا توجد أصناف مباعة في هذه الفترة')
                          : Column(
                              children: [
                                for (final product in data.top)
                                  Padding(
                                    padding: const EdgeInsets.symmetric(
                                      vertical: 12,
                                    ),
                                    child: Column(
                                      crossAxisAlignment:
                                          CrossAxisAlignment.stretch,
                                      children: [
                                        Text(
                                          product.name,
                                          style: Theme.of(context)
                                              .textTheme
                                              .titleMedium,
                                        ),
                                        InventoryValue(product.code),
                                        InventoryFacts([
                                          (
                                            'صافي الكمية',
                                            '${formatQuantity(product.quantity)} ${product.unit}',
                                          ),
                                          (
                                            'صافي الإيراد',
                                            formatKwd(product.revenue),
                                          ),
                                          if (state.access.reportProfit &&
                                              product.profit != null)
                                            (
                                              'الربح',
                                              formatKwd(product.profit!),
                                            ),
                                        ]),
                                        const Divider(),
                                      ],
                                    ),
                                  ),
                              ],
                            ),
                    ),
                  ] else if (state.loading)
                    const Padding(
                      padding: EdgeInsets.all(24),
                      child: Text('جارٍ تحميل بيانات المبيعات…'),
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
