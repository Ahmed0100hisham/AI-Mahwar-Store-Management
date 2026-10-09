import 'package:flutter/material.dart';

import '../data/sales_models.dart';

String isolateDate(String value) => '\u2066$value\u2069';

// Keep long references readable at increased text scale instead of shrinking.
class SalesIdentifier extends StatelessWidget {
  const SalesIdentifier(this.value, {super.key, this.style});
  final String value;
  final TextStyle? style;
  @override
  Widget build(BuildContext context) => Text(
    value,
    textDirection: TextDirection.ltr,
    softWrap: true,
    style: style ?? Theme.of(context).textTheme.titleMedium,
  );
}

class SalesRangeControls extends StatelessWidget {
  const SalesRangeControls({
    super.key,
    required this.range,
    required this.onChanged,
    required this.onRefresh,
    required this.loading,
  });
  final SalesRange range;
  final ValueChanged<SalesRange> onChanged;
  final VoidCallback onRefresh;
  final bool loading;
  Future<void> _custom(BuildContext context) async {
    var from = range.from ?? '', to = range.to ?? '';
    String? error;
    final result = await showDialog<SalesRange>(
      context: context,
      builder: (context) => StatefulBuilder(
        builder: (context, setDialog) => AlertDialog(
          title: const Text('فترة المبيعات والفواتير'),
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
                  onChanged: (value) => from = value,
                ),
                const SizedBox(height: 12),
                TextFormField(
                  initialValue: to,
                  textDirection: TextDirection.ltr,
                  decoration: const InputDecoration(
                    labelText: 'إلى (yyyy-MM-dd)',
                  ),
                  onChanged: (value) => to = value,
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
                    SalesRange.custom(from.trim(), to.trim()),
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
    if (result != null) {
      onChanged(result);
    }
  }

  @override
  Widget build(BuildContext context) => Wrap(
    spacing: 12,
    runSpacing: 12,
    crossAxisAlignment: WrapCrossAlignment.center,
    children: [
      SizedBox(
        width: 210,
        child: DropdownButtonFormField<SalesPeriod>(
          key: ValueKey(range),
          initialValue: range.period,
          isExpanded: true,
          decoration: const InputDecoration(labelText: 'الفترة'),
          hint: const Text('فترة مخصصة'),
          items: SalesPeriod.values
              .map(
                (period) =>
                    DropdownMenuItem(value: period, child: Text(period.label)),
              )
              .toList(),
          onChanged: (value) {
            if (value != null) {
              onChanged(SalesRange.preset(value));
            }
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

class SalesSection extends StatelessWidget {
  const SalesSection({
    super.key,
    required this.title,
    required this.child,
    this.note,
  });
  final String title;
  final String? note;
  final Widget child;
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 16),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(title, style: Theme.of(context).textTheme.titleLarge),
        if (note != null)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 8),
            child: Text(note!, style: Theme.of(context).textTheme.bodySmall),
          ),
        const SizedBox(height: 8),
        child,
      ],
    ),
  );
}
