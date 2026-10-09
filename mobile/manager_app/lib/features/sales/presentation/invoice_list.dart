import 'package:flutter/material.dart';

import '../../../core/utils/money.dart';
import '../../auth/state/auth_controller.dart';
import '../../inventory/presentation/inventory_widgets.dart';
import '../data/sales_models.dart';
import '../data/sales_repository.dart';
import '../state/sales_controller.dart';
import 'invoice_detail_screen.dart';
import 'sales_widgets.dart';

class InvoiceList extends StatefulWidget {
  const InvoiceList({super.key, required this.auth});
  final AuthController auth;
  @override
  State<InvoiceList> createState() => _InvoiceListState();
}

class _InvoiceListState extends State<InvoiceList> {
  late final InvoiceListController state;
  final search = TextEditingController();
  @override
  void initState() {
    super.initState();
    state = InvoiceListController(
      widget.auth,
      SalesRepository(widget.auth.repository.client),
    );
    state.load();
  }

  @override
  void dispose() {
    search.dispose();
    state.dispose();
    super.dispose();
  }

  Future<void> _open(SalesInvoice invoice) async {
    final customer = await Navigator.push<InvoiceCustomer>(
      context,
      MaterialPageRoute(
        builder: (_) => InvoiceDetailScreen(auth: widget.auth, id: invoice.id),
      ),
    );
    if (mounted && state.allowed && customer != null) {
      state.setCustomer(customer);
    }
  }

  Widget _header(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      Text('الفواتير', style: Theme.of(context).textTheme.headlineSmall),
      const SizedBox(height: 16),
      SalesRangeControls(
        range: state.range,
        onChanged: state.setRange,
        onRefresh: state.load,
        loading: state.loading,
      ),
      const SizedBox(height: 16),
      const Text('بحث برقم الفاتورة أو اسم العميل أو كوده أو هاتفه'),
      const SizedBox(height: 8),
      TextField(
        controller: search,
        maxLength: 100,
        textInputAction: TextInputAction.search,
        decoration: InputDecoration(
          hintText: 'اكتب أو الصق هنا',
          prefixIcon: const Icon(Icons.search),
          suffixIcon: IconButton(
            tooltip: 'مسح البحث',
            onPressed: () {
              search.clear();
              state.setSearch('');
            },
            icon: const Icon(Icons.clear),
          ),
        ),
        onChanged: state.setSearch,
        onSubmitted: (value) => state.setSearch(value, immediate: true),
      ),
      Wrap(
        spacing: 12,
        runSpacing: 12,
        children: [
          SizedBox(
            width: 210,
            child: DropdownButtonFormField<InvoiceStatus?>(
              key: ValueKey(('status', state.status)),
              initialValue: state.status,
              isExpanded: true,
              decoration: const InputDecoration(labelText: 'حالة الفاتورة'),
              items: [
                const DropdownMenuItem(value: null, child: Text('كل الحالات')),
                ...InvoiceStatus.values.map(
                  (value) =>
                      DropdownMenuItem(value: value, child: Text(value.label)),
                ),
              ],
              onChanged: state.setStatus,
            ),
          ),
          SizedBox(
            width: 210,
            child: DropdownButtonFormField<InvoicePayment?>(
              key: ValueKey(('payment', state.payment)),
              initialValue: state.payment,
              isExpanded: true,
              decoration: const InputDecoration(labelText: 'طريقة الدفع'),
              items: [
                const DropdownMenuItem(
                  value: null,
                  child: Text('كل طرق الدفع'),
                ),
                ...InvoicePayment.values.map(
                  (value) =>
                      DropdownMenuItem(value: value, child: Text(value.label)),
                ),
              ],
              onChanged: state.setPayment,
            ),
          ),
          SizedBox(
            width: 210,
            child: DropdownButtonFormField<InvoiceSort>(
              key: ValueKey(state.sort),
              initialValue: state.sort,
              isExpanded: true,
              decoration: const InputDecoration(labelText: 'الترتيب'),
              items: InvoiceSort.values
                  .map(
                    (value) => DropdownMenuItem(
                      value: value,
                      child: Text(value.label),
                    ),
                  )
                  .toList(),
              onChanged: (value) {
                if (value != null) {
                  state.setSort(value);
                }
              },
            ),
          ),
        ],
      ),
      if (state.customer != null)
        Wrap(
          crossAxisAlignment: WrapCrossAlignment.center,
          spacing: 12,
          children: [
            Text('العميل: ${state.customer!.name}'),
            TextButton(
              onPressed: () => state.setCustomer(null),
              child: const Text('كل العملاء'),
            ),
          ],
        ),
      const SizedBox(height: 16),
      if (state.loading || state.searching)
        const LinearProgressIndicator(semanticsLabel: 'جارٍ تحميل الفواتير'),
      if (state.error != null)
        InventoryError(state.error!, onRetry: state.load),
      if (state.loaded) ...[
        Text('النتائج المعروضة: ${state.loadedContext}'),
        Text('تم تحميل ${state.items.length} • إجمالي النتائج ${state.total}'),
        if (state.error != null)
          const Text('تعذر تطبيق الطلب. هذه نتائج آخر تحميل ناجح.'),
        if (state.items.isEmpty &&
            !state.loading &&
            !state.searching &&
            state.error == null)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 32),
            child: Text(
              state.search.isEmpty
                  ? 'لا توجد فواتير مطابقة لهذه الفترة والمرشحات'
                  : 'لا توجد نتائج مطابقة',
              textAlign: TextAlign.center,
            ),
          ),
      ] else if (state.loading)
        const Padding(
          padding: EdgeInsets.all(24),
          child: Text('جارٍ تحميل الفواتير…'),
        ),
    ],
  );
  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: state,
    builder: (context, _) {
      if (!state.allowed) {
        return const InventoryBlocked();
      }
      return Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 1000),
          child: RefreshIndicator(
            onRefresh: state.load,
            child: ListView.builder(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.all(16),
              keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
              itemCount: state.items.length + 2,
              itemBuilder: (context, index) {
                if (index == 0) {
                  return _header(context);
                }
                if (index <= state.items.length) {
                  final invoice = state.items[index - 1];
                  return Semantics(
                    button: true,
                    child: InkWell(
                      onTap: () => _open(invoice),
                      child: Container(
                        padding: const EdgeInsets.symmetric(
                          vertical: 16,
                          horizontal: 8,
                        ),
                        decoration: BoxDecoration(
                          border: Border(
                            bottom: BorderSide(
                              color: Theme.of(context)
                                  .colorScheme
                                  .outlineVariant,
                            ),
                          ),
                        ),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.stretch,
                          children: [
                            SalesIdentifier(
                              invoice.number,
                              style: Theme.of(context).textTheme.titleMedium,
                            ),
                            Text(invoice.customer.name),
                            InventoryValue(invoice.date.replaceFirst('T', ' ')),
                            InventoryFacts([
                              ('إجمالي الفاتورة', formatKwd(invoice.total)),
                              (
                                'حالة الدفع',
                                paymentStateLabel(invoice.paymentStatus),
                              ),
                              (
                                'حالة الفاتورة',
                                invoiceStateLabel(invoice.status),
                              ),
                              if (invoice.returnedAmount != '0.000')
                                (
                                  'صافي القيمة بعد المرتجعات',
                                  formatKwd(invoice.netAmount),
                                ),
                            ]),
                            Text(
                              'المرتجعات محتسبة حتى ${isolateDate(invoice.returnCutoff!.iso)}',
                              style: Theme.of(context).textTheme.bodySmall,
                            ),
                            const Text(
                              'عرض الفاتورة',
                              style: TextStyle(
                                decoration: TextDecoration.underline,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  );
                }
                return Padding(
                  padding: const EdgeInsets.symmetric(vertical: 16),
                  child: Column(
                    children: [
                      if (state.loadingMore)
                        const CircularProgressIndicator(
                          semanticsLabel: 'جارٍ تحميل المزيد من الفواتير',
                        ),
                      if (state.nextError != null)
                        InventoryError(
                          state.nextError!,
                          onRetry: state.nextPage,
                        ),
                      if (state.canLoadMore &&
                          !state.loadingMore &&
                          state.nextError == null)
                        OutlinedButton(
                          onPressed: state.nextPage,
                          child: const Text('تحميل المزيد من الفواتير'),
                        ),
                      if (state.loaded &&
                          !state.hasMore &&
                          state.items.isNotEmpty)
                        const Text('نهاية النتائج'),
                    ],
                  ),
                );
              },
            ),
          ),
        ),
      );
    },
  );
}
