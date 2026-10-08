import 'package:flutter/material.dart';

import '../../../core/errors/app_failure.dart';
import '../../../shared/widgets/states.dart';
import '../state/inventory_controller.dart';

// Keep the shared safe message, with retry on its own line for narrow RTL phones.
class InventoryError extends StatelessWidget {
  const InventoryError(this.failure, {super.key, required this.onRetry});
  final AppFailure failure;
  final VoidCallback onRetry;
  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      ErrorNotice(failure),
      Align(
        alignment: AlignmentDirectional.centerEnd,
        child: TextButton(
          onPressed: onRetry,
          child: const Text('إعادة المحاولة'),
        ),
      ),
    ],
  );
}

class InventoryValue extends StatelessWidget {
  const InventoryValue(this.value, {super.key, this.style});
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
      style:
          style ??
          const TextStyle(fontFeatures: [FontFeature.tabularFigures()]),
    ),
  );
}

class InventoryFacts extends StatelessWidget {
  const InventoryFacts(this.facts, {super.key});
  final List<(String, String)> facts;
  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      for (final fact in facts)
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 5),
          child: Wrap(
            alignment: WrapAlignment.spaceBetween,
            spacing: 16,
            runSpacing: 4,
            children: [Text(fact.$1), InventoryValue(fact.$2)],
          ),
        ),
    ],
  );
}

class InventoryRows<T> extends StatelessWidget {
  const InventoryRows({
    super.key,
    required this.state,
    required this.header,
    required this.row,
    required this.empty,
  });
  final InventoryPaged<T> state;
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
              const SizedBox(height: 12),
              if (state.loading || state.searching)
                const LinearProgressIndicator(
                  semanticsLabel: 'جارٍ تحميل النتائج',
                ),
              if (state.error != null) ...[
                const SizedBox(height: 12),
                InventoryError(state.error!, onRetry: state.load),
              ],
              if (state.loaded)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 12),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('النتائج المعروضة: ${state.loadedContext}'),
                      Text(
                        'تم تحميل ${state.items.length} • إجمالي النتائج ${state.total}',
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                      if (state.error != null)
                        const Text(
                          'تعذر تطبيق الطلب. هذه نتائج آخر تحميل ناجح.',
                        ),
                    ],
                  ),
                ),
              if (state.items.isEmpty &&
                  state.loaded &&
                  !state.loading &&
                  !state.searching &&
                  state.error == null)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 32),
                  child: Text(empty, textAlign: TextAlign.center),
                ),
              if (!state.loaded && state.loading)
                const Padding(
                  padding: EdgeInsets.symmetric(vertical: 32),
                  child: Text(
                    'جارٍ تحميل البيانات…',
                    textAlign: TextAlign.center,
                  ),
                ),
            ],
          );
        }
        if (index <= state.items.length) return row(state.items[index - 1]);
        return Padding(
          padding: const EdgeInsets.symmetric(vertical: 16),
          child: Column(
            children: [
              if (state.loadingMore)
                const CircularProgressIndicator(
                  semanticsLabel: 'جارٍ تحميل المزيد',
                ),
              if (state.nextError != null)
                InventoryError(state.nextError!, onRetry: state.nextPage),
              if (state.canLoadMore &&
                  !state.loadingMore &&
                  state.nextError == null)
                OutlinedButton(
                  onPressed: state.nextPage,
                  child: const Text('تحميل المزيد'),
                ),
              if (state.loaded && !state.hasMore && state.items.isNotEmpty)
                const Text('نهاية النتائج'),
            ],
          ),
        );
      },
    ),
  );
}

class InventoryBlocked extends StatelessWidget {
  const InventoryBlocked({super.key});
  @override
  Widget build(BuildContext context) => const Center(
    child: Padding(
      padding: EdgeInsets.all(24),
      child: Text(
        'هذه الصفحة غير متاحة ضمن صلاحيات جلستك الحالية.',
        textAlign: TextAlign.center,
      ),
    ),
  );
}
