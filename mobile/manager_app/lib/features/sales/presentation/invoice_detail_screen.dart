import 'package:flutter/material.dart';

import '../../../core/utils/money.dart';
import '../../auth/state/auth_controller.dart';
import '../../inventory/presentation/inventory_widgets.dart';
import '../data/sales_models.dart';
import '../data/sales_repository.dart';
import '../state/sales_controller.dart';
import 'sales_widgets.dart';

class InvoiceDetailScreen extends StatefulWidget {
  const InvoiceDetailScreen({super.key, required this.auth, required this.id});
  final AuthController auth;
  final int id;
  @override
  State<InvoiceDetailScreen> createState() => _InvoiceDetailScreenState();
}

class _InvoiceDetailScreenState extends State<InvoiceDetailScreen> {
  late final InvoiceDetailController state;
  @override
  void initState() {
    super.initState();
    state = InvoiceDetailController(
      widget.auth,
      SalesRepository(widget.auth.repository.client),
      widget.id,
    );
    state.load();
  }

  @override
  void didUpdateWidget(covariant InvoiceDetailScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.id != oldWidget.id) {
      state.selectInvoice(widget.id);
    }
  }

  @override
  void dispose() {
    state.dispose();
    super.dispose();
  }

  Widget _paging<T>(
    DetailCollection<T> collection,
    VoidCallback next,
    String label,
  ) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      Text('تم تحميل ${collection.items.length} من ${collection.total}'),
      if (collection.busy)
        LinearProgressIndicator(semanticsLabel: 'جارٍ تحميل $label'),
      if (collection.error != null)
        InventoryError(collection.error!, onRetry: next),
      if (collection.hasMore && !collection.busy && collection.error == null)
        OutlinedButton(
          onPressed: state.loading || state.error != null ? null : next,
          child: Text('تحميل المزيد من $label'),
        ),
    ],
  );
  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: state,
    builder: (context, _) => Scaffold(
      appBar: AppBar(
        title: const Text('تفاصيل الفاتورة'),
        actions: [
          IconButton(
            tooltip: 'تحديث الفاتورة',
            onPressed: state.loading ? null : state.load,
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
      body: Builder(
        builder: (context) {
          if (!state.allowed) {
            return const InventoryBlocked();
          }
          final invoice = state.invoice;
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
                      if (state.loading)
                        const LinearProgressIndicator(
                          semanticsLabel: 'جارٍ تحميل تفاصيل الفاتورة',
                        ),
                      if (state.error != null)
                        InventoryError(state.error!, onRetry: state.load),
                      if (state.error != null && invoice != null)
                        const Text('تعذر التحديث. هذه بيانات آخر تحميل ناجح.'),
                      if (invoice != null) ...[
                        SalesIdentifier(
                          invoice.number,
                          style: Theme.of(context).textTheme.headlineSmall,
                        ),
                        InventoryValue(invoice.date.replaceFirst('T', ' ')),
                        SalesSection(
                          title: 'الفاتورة والعميل',
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.stretch,
                            children: [
                              Text(
                                invoice.customer.name,
                                style: Theme.of(context).textTheme.titleMedium,
                              ),
                              InventoryFacts([
                                ('كود العميل', invoice.customer.code),
                                if (invoice.customer.phone != null)
                                  ('الهاتف', invoice.customer.phone!),
                                ('أنشأها', invoice.creatorName),
                                (
                                  'حالة الفاتورة',
                                  invoiceStateLabel(invoice.status),
                                ),
                                ('التسعير', priceTypeLabel(invoice.priceType)),
                              ]),
                              TextButton(
                                onPressed: () =>
                                    Navigator.pop(context, invoice.customer),
                                child: const Text('فواتير هذا العميل'),
                              ),
                            ],
                          ),
                        ),
                        const Divider(),
                        SalesSection(
                          title: 'القيم الأصلية والدفع',
                          note: 'المدفوع والمتبقي لقطة الفاتورة الأصلية؛ المتبقي ليس مديونية العميل الحالية بعد المرتجعات.',
                          child: InventoryFacts([
                            (
                              'المجموع قبل خصم الفاتورة',
                              formatKwd(invoice.subtotal),
                            ),
                            ('خصم الفاتورة', formatKwd(invoice.discount)),
                            ('الضريبة المخزنة', formatKwd(invoice.tax)),
                            ('إجمالي الفاتورة', formatKwd(invoice.total)),
                            ('المدفوع الأصلي', formatKwd(invoice.paid)),
                            ('المتبقي الأصلي', formatKwd(invoice.remaining)),
                            (
                              'حالة الدفع',
                              paymentStateLabel(invoice.paymentStatus),
                            ),
                            (
                              'طريقة الدفع',
                              paymentMethodLabel(invoice.paymentMethod),
                            ),
                          ]),
                        ),
                        const Divider(),
                        SalesSection(
                          title: 'المرتجعات المسجلة',
                          note: 'تشمل كل التواريخ المسجلة، بما فيها المرتجعات اللاحقة لتاريخ الفاتورة. قيمة المرتجع تختلف عن المبلغ المردود نقداً.',
                          child: InventoryFacts([
                            (
                              'إجمالي قيمة المرتجعات',
                              formatKwd(invoice.returnedAmount),
                            ),
                            (
                              'المبالغ المردودة',
                              formatKwd(invoice.refundedAmount),
                            ),
                            (
                              'صافي قيمة الفاتورة بعد المرتجعات',
                              formatKwd(invoice.netAmount),
                            ),
                          ]),
                        ),
                        if ((state.access.invoiceCost &&
                                invoice.historicalCost != null) ||
                            (state.access.invoiceProfit &&
                                invoice.grossProfit != null))
                          SalesSection(
                            title: 'القيم التاريخية',
                            note:
                                state.access.invoiceProfit &&
                                    invoice.grossProfit != null
                                ? 'الربح الأصلي للفاتورة المرحّلة قبل المرتجعات؛ لا يمثل صافي الربح الحالي.'
                                : 'التكلفة المخزنة وقت الفاتورة، وليست تكلفة المخزون الحالية.',
                            child: InventoryFacts([
                              if (state.access.invoiceCost &&
                                  invoice.historicalCost != null)
                                (
                                  'التكلفة التاريخية الأصلية',
                                  formatKwd(invoice.historicalCost!),
                                ),
                              if (state.access.invoiceProfit &&
                                  invoice.grossProfit != null)
                                (
                                  'الربح الإجمالي الأصلي',
                                  formatKwd(invoice.grossProfit!),
                                ),
                            ]),
                          ),
                        const Divider(),
                        SalesSection(
                          title: 'بنود الفاتورة',
                          note: 'الكميات والأسعار والخصم وإجمالي البند قيم مخزنة. كمية المرتجع لكل بند تشمل كل التواريخ. أسماء المنتجات والوحدات هي التسميات الحالية.',
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.stretch,
                            children: [
                              if (state.lines.items.isEmpty)
                                const Text('لا توجد بنود لهذه الفاتورة'),
                              for (final line in state.lines.items)
                                Padding(
                                  padding: const EdgeInsets.symmetric(
                                    vertical: 12,
                                  ),
                                  child: Column(
                                    crossAxisAlignment:
                                        CrossAxisAlignment.stretch,
                                    children: [
                                      Text(
                                        line.name,
                                        style: Theme.of(context)
                                            .textTheme
                                            .titleMedium,
                                      ),
                                      InventoryValue(line.productCode),
                                      InventoryFacts([
                                        (
                                          'الكمية',
                                          '${formatQuantity(line.quantity)} ${line.unit}',
                                        ),
                                        (
                                          'سعر الوحدة',
                                          formatKwd(line.unitPrice),
                                        ),
                                        (
                                          'خصم البند بالكامل',
                                          formatKwd(line.discount),
                                        ),
                                        (
                                          'إجمالي البند المخزن',
                                          formatKwd(line.total),
                                        ),
                                        (
                                          'الكمية المرتجعة',
                                          '${formatQuantity(line.returnedQuantity)} ${line.unit}',
                                        ),
                                        if (state.access.invoiceCost &&
                                            line.historicalUnitCost != null)
                                          (
                                            'تكلفة الوحدة التاريخية',
                                            formatKwd(line.historicalUnitCost!),
                                          ),
                                      ]),
                                      const Divider(),
                                    ],
                                  ),
                                ),
                              _paging(state.lines, state.nextLines, 'البنود'),
                            ],
                          ),
                        ),
                        SalesSection(
                          title: 'مستندات مرتجعات هذه الفاتورة',
                          note: 'هذه مرتجعات الفاتورة المختارة؛ ليست قائمة كل مرتجعات الفترة.',
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.stretch,
                            children: [
                              if (state.returns.items.isEmpty)
                                const Text(
                                  'لا توجد مرتجعات مسجلة لهذه الفاتورة',
                                ),
                              for (final returned in state.returns.items)
                                Padding(
                                  padding: const EdgeInsets.symmetric(
                                    vertical: 12,
                                  ),
                                  child: Column(
                                    crossAxisAlignment:
                                        CrossAxisAlignment.stretch,
                                    children: [
                                      SalesIdentifier(
                                        returned.number,
                                        style: Theme.of(context)
                                            .textTheme
                                            .titleMedium,
                                      ),
                                      InventoryValue(
                                        returned.date.replaceFirst('T', ' '),
                                      ),
                                      InventoryFacts([
                                        (
                                          'قيمة المرتجع',
                                          formatKwd(returned.total),
                                        ),
                                        (
                                          'المبلغ المردود',
                                          formatKwd(returned.refund),
                                        ),
                                        if (returned.refundMethod != null)
                                          (
                                            'طريقة رد المبلغ',
                                            paymentMethodLabel(
                                              returned.refundMethod!,
                                            ),
                                          ),
                                      ]),
                                      const Divider(),
                                    ],
                                  ),
                                ),
                              _paging(
                                state.returns,
                                state.nextReturns,
                                'المرتجعات',
                              ),
                            ],
                          ),
                        ),
                      ] else if (state.loading)
                        const Padding(
                          padding: EdgeInsets.all(24),
                          child: Text('جارٍ تحميل الفاتورة…'),
                        ),
                    ],
                  ),
                ),
              ),
            ),
          );
        },
      ),
    ),
  );
}
