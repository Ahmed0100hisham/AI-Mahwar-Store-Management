import 'package:flutter/material.dart';

import '../../../core/utils/money.dart';
import '../../inventory/presentation/inventory_widgets.dart'
    show InventoryError, InventoryValue;
import '../../sales/presentation/sales_widgets.dart' show SalesIdentifier;
import '../data/party_models.dart';
import '../state/party_controller.dart';

class PartyBlocked extends StatelessWidget {
  const PartyBlocked({super.key});
  @override
  Widget build(BuildContext context) => const Center(
    child: Padding(
      padding: EdgeInsets.all(24),
      child: Text(
        'ليست لديك صلاحية عرض هذه البيانات.',
        textAlign: TextAlign.center,
      ),
    ),
  );
}

class PartyMoney extends StatelessWidget {
  const PartyMoney(this.label, this.value, {super.key});
  final String label, value;
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 6),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(label, style: Theme.of(context).textTheme.bodySmall),
        const SizedBox(height: 4),
        InventoryValue(
          formatKwd(value),
          style: Theme.of(context).textTheme.titleMedium
              ?.copyWith(fontFeatures: const [FontFeature.tabularFigures()]),
        ),
      ],
    ),
  );
}

class PartyRows<T, D> extends StatelessWidget {
  const PartyRows({
    super.key,
    required this.state,
    required this.header,
    required this.row,
    required this.empty,
  });
  final PartyPaged<T, D> state;
  final Widget header;
  final Widget Function(T) row;
  final String empty;
  @override
  Widget build(BuildContext context) => RefreshIndicator(
    onRefresh: state.load,
    child: ListView.builder(
      physics: const AlwaysScrollableScrollPhysics(),
      padding: const EdgeInsets.all(16),
      keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
      itemCount: state.items.length + 2,
      itemBuilder: (context, index) {
        if (index == 0) {
          return Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              header,
              if (state.loading || state.searching) ...[
                const SizedBox(height: 12),
                const LinearProgressIndicator(
                  semanticsLabel: 'جارٍ تحميل النتائج',
                ),
                Text(
                  state.searching
                      ? 'جارٍ البحث…'
                      : state.loaded
                      ? 'جارٍ تحديث البيانات…'
                      : 'جارٍ تحميل البيانات…',
                ),
              ],
              if (state.error != null)
                InventoryError(state.error!, onRetry: state.load),
              if (state.loaded)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 12),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Text('النتائج المعروضة: ${state.loadedContext}'),
                      Text(
                        'تم تحميل ${state.items.length} • إجمالي النتائج ${state.total}',
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                      if (state.error != null)
                        const Text('تعذر التحديث. هذه نتائج آخر تحميل ناجح.'),
                    ],
                  ),
                ),
              if (state.loaded &&
                  state.items.isEmpty &&
                  !state.loading &&
                  !state.searching &&
                  state.error == null)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 32),
                  child: Text(empty, textAlign: TextAlign.center),
                ),
            ],
          );
        }
        if (index <= state.items.length) return row(state.items[index - 1]);
        return Padding(
          padding: const EdgeInsets.symmetric(vertical: 16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (state.loadingMore)
                const LinearProgressIndicator(
                  semanticsLabel: 'جارٍ تحميل الصفحة التالية',
                ),
              if (state.nextError != null)
                InventoryError(state.nextError!, onRetry: state.nextPage)
              else if (state.hasMore)
                OutlinedButton(
                  onPressed: state.canLoadMore && !state.loadingMore
                      ? state.nextPage
                      : null,
                  child: const Text('تحميل المزيد'),
                )
              else if (state.loaded && state.items.isNotEmpty)
                const Text('نهاية النتائج', textAlign: TextAlign.center),
            ],
          ),
        );
      },
    ),
  );
}

class PartyRangeControls extends StatelessWidget {
  const PartyRangeControls({
    super.key,
    required this.range,
    required this.onChanged,
    required this.onRefresh,
    required this.loading,
  });
  final PartyRange range;
  final ValueChanged<PartyRange> onChanged;
  final VoidCallback onRefresh;
  final bool loading;
  Future<void> _custom(BuildContext context) async {
    var from = range.from ?? '', to = range.to ?? '';
    String? error;
    final result = await showDialog<PartyRange>(
      context: context,
      builder: (context) => StatefulBuilder(
        builder: (context, setDialog) => AlertDialog(
          title: const Text('فترة كشف الحساب'),
          content: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Text(
                  'تاريخ الأعمال بتوقيت الكويت؛ حتى 366 يوماً، شامل اليومين.',
                ),
                TextFormField(
                  initialValue: from,
                  textDirection: TextDirection.ltr,
                  decoration: const InputDecoration(
                    labelText: 'من (yyyy-MM-dd)',
                  ),
                  onChanged: (v) => from = v,
                ),
                const SizedBox(height: 12),
                TextFormField(
                  initialValue: to,
                  textDirection: TextDirection.ltr,
                  decoration: const InputDecoration(
                    labelText: 'إلى (yyyy-MM-dd)',
                  ),
                  onChanged: (v) => to = v,
                ),
                if (error != null) Text(error!),
              ],
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context),
              child: const Text('إلغاء'),
            ),
            FilledButton(
              onPressed: () {
                try {
                  Navigator.pop(
                    context,
                    PartyRange.custom(from.trim(), to.trim()),
                  );
                } catch (_) {
                  setDialog(
                    () => error = 'أدخل تاريخين صحيحين مرتبين، بفترة لا تتجاوز 366 يوماً.',
                  );
                }
              },
              child: const Text('تطبيق'),
            ),
          ],
        ),
      ),
    );
    if (result != null) onChanged(result);
  }

  @override
  Widget build(BuildContext context) => Wrap(
    spacing: 12,
    runSpacing: 12,
    crossAxisAlignment: WrapCrossAlignment.center,
    children: [
      SizedBox(
        width: 210,
        child: DropdownButtonFormField<PartyPeriod>(
          key: ValueKey(range),
          initialValue: range.period,
          isExpanded: true,
          decoration: const InputDecoration(labelText: 'الفترة'),
          hint: const Text('فترة مخصصة'),
          items: PartyPeriod.values
              .map((v) => DropdownMenuItem(value: v, child: Text(v.label)))
              .toList(),
          onChanged: (v) {
            if (v != null) onChanged(PartyRange.preset(v));
          },
        ),
      ),
      OutlinedButton(
        onPressed: () => _custom(context),
        child: const Text('تحديد تاريخين'),
      ),
      OutlinedButton.icon(
        onPressed: loading ? null : onRefresh,
        icon: const Icon(Icons.refresh),
        label: const Text('تحديث البيانات'),
      ),
    ],
  );
}

class PartyEntryRow extends StatelessWidget {
  const PartyEntryRow(this.entry, {super.key});
  final PartyEntry entry;
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 12),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const Divider(),
        Text(
          accountTypeLabel(entry.type),
          style: Theme.of(context).textTheme.titleMedium,
        ),
        SalesIdentifier(
          entry.date,
          style: Theme.of(context).textTheme.bodySmall,
        ),
        if (entry.reference != null && entry.reference!.isNotEmpty) ...[
          const Text('المرجع'),
          SalesIdentifier(entry.reference!),
        ],
        LayoutBuilder(
          builder: (context, box) {
            final wide =
                box.maxWidth / MediaQuery.textScalerOf(context).scale(1) >= 550;
            final values = [
              PartyMoney('مدين', entry.debit),
              PartyMoney('دائن', entry.credit),
              PartyMoney('الرصيد بعد الحركة', entry.runningBalance),
            ];
            return wide
                ? Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      for (final value in values) Expanded(child: value),
                    ],
                  )
                : Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: values,
                  );
          },
        ),
      ],
    ),
  );
}
