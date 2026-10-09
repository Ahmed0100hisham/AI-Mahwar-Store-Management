import 'package:flutter/material.dart';

import '../../inventory/data/inventory_models.dart';
import '../../inventory/presentation/inventory_widgets.dart';
import '../../parties/presentation/party_widgets.dart' show PartyBlocked;
import '../state/feature_state.dart';

class FeatureRows<T> extends StatelessWidget {
  const FeatureRows({
    super.key,
    required this.state,
    required this.header,
    required this.row,
  });
  final FeaturePaged<T> state;
  final Widget header;
  final Widget Function(T) row;
  @override
  Widget build(BuildContext context) {
    if (!state.allowed) return const PartyBlocked();
    return RefreshIndicator(
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
                if (state.loading || state.searching)
                  const LinearProgressIndicator(
                    semanticsLabel: 'جارٍ تحميل النتائج',
                  ),
                if (state.error != null)
                  InventoryError(state.error!, onRetry: state.load),
                if (state.data != null)
                  Text(
                    'المحمّل ${state.items.length} • إجمالي النتائج ${state.total}',
                  ),
                if (state.data != null && state.error != null)
                  const Text('هذه نتائج آخر تحميل ناجح؛ تعذر تحديثها.'),
                if (state.data != null &&
                    state.items.isEmpty &&
                    !state.loading &&
                    state.error == null)
                  const Padding(
                    padding: EdgeInsets.all(24),
                    child: Text('لا توجد نتائج.'),
                  ),
              ],
            );
          }
          if (index <= state.items.length) return row(state.items[index - 1]);
          return Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (state.moreLoading) const LinearProgressIndicator(),
              if (state.moreError != null)
                InventoryError(state.moreError!, onRetry: state.nextPage)
              else if (state.hasMore)
                OutlinedButton(
                  onPressed:
                      state.loading || state.moreLoading || state.error != null
                      ? null
                      : state.nextPage,
                  child: const Text('تحميل المزيد'),
                )
              else if (state.items.isNotEmpty)
                const Text('نهاية النتائج', textAlign: TextAlign.center),
            ],
          );
        },
      ),
    );
  }
}

class FeatureBody<T> extends StatelessWidget {
  const FeatureBody({
    super.key,
    required this.state,
    required this.header,
    required this.content,
  });
  final FeatureState<T> state;
  final Widget header;
  final Widget Function(T) content;
  @override
  Widget build(BuildContext context) {
    if (!state.allowed) return const PartyBlocked();
    return RefreshIndicator(
      onRefresh: state.load,
      child: ListView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.all(16),
        children: [
          header,
          if (state.loading) const LinearProgressIndicator(),
          if (state.error != null)
            InventoryError(state.error!, onRetry: state.load),
          if (state.data != null && state.error != null)
            const Text('هذه بيانات آخر تحميل ناجح؛ تعذر تحديثها.'),
          if (state.data != null) content(state.data as T),
        ],
      ),
    );
  }
}

class FeatureChoice<T> extends StatelessWidget {
  const FeatureChoice({
    super.key,
    required this.label,
    required this.value,
    required this.choices,
    required this.onChanged,
    this.enabled = true,
  });
  final String label;
  final T? value;
  final Map<T, String> choices;
  final ValueChanged<T?> onChanged;
  final bool enabled;
  @override
  Widget build(BuildContext context) => SizedBox(
    width: 245,
    child: DropdownButtonFormField<T>(
      key: ValueKey((label, value)),
      initialValue: value,
      hint: Text(choices[value] ?? 'اختر'),
      isExpanded: true,
      decoration: InputDecoration(labelText: label),
      items: choices.entries
          .map(
            (e) => DropdownMenuItem(
              value: e.key,
              child: Text(e.value, overflow: TextOverflow.ellipsis),
            ),
          )
          .toList(),
      onChanged: enabled ? onChanged : null,
    ),
  );
}

class FeatureRangeControls extends StatelessWidget {
  const FeatureRangeControls({
    super.key,
    required this.range,
    required this.changed,
    required this.refresh,
    required this.loading,
  });
  final MovementRange range;
  final ValueChanged<MovementRange> changed;
  final VoidCallback refresh;
  final bool loading;
  Future<void> _custom(BuildContext context) async {
    var from = range.from ?? '', to = range.to ?? '';
    String? error;
    final result = await showDialog<MovementRange>(
      context: context,
      builder: (context) => StatefulBuilder(
        builder: (context, update) => AlertDialog(
          title: const Text('فترة التقرير'),
          content: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Text('تواريخ الأعمال؛ حد أقصى 366 يوماً شاملاً اليومين.'),
                TextFormField(
                  initialValue: from,
                  textDirection: TextDirection.ltr,
                  decoration: const InputDecoration(
                    labelText: 'من (yyyy-MM-dd)',
                  ),
                  onChanged: (v) => from = v,
                ),
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
                    MovementRange.custom(from.trim(), to.trim()),
                  );
                } catch (_) {
                  update(
                    () => error =
                        'أدخل تاريخين صحيحين بفترة لا تتجاوز 366 يوماً.',
                  );
                }
              },
              child: const Text('تطبيق'),
            ),
          ],
        ),
      ),
    );
    if (result != null) changed(result);
  }

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 12),
    child: Wrap(
      spacing: 12,
      runSpacing: 12,
      children: [
        FeatureChoice<MovementPeriod>(
          label: 'الفترة',
          value: range.period,
          choices: {for (final v in MovementPeriod.values) v: v.label},
          onChanged: (v) {
            if (v != null) changed(MovementRange.preset(v));
          },
        ),
        OutlinedButton(
          onPressed: () => _custom(context),
          child: const Text('تحديد تاريخين'),
        ),
        OutlinedButton.icon(
          onPressed: loading ? null : refresh,
          icon: const Icon(Icons.refresh),
          label: const Text('تحديث البيانات'),
        ),
        Text(range.label),
      ],
    ),
  );
}
