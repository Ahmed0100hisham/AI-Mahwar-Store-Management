import 'package:flutter/material.dart';

import '../../../core/utils/money.dart';
import '../../auth/state/auth_controller.dart';
import '../data/inventory_models.dart';
import '../data/inventory_repository.dart';
import '../state/inventory_controller.dart';
import 'inventory_widgets.dart';

class MovementScreen extends StatefulWidget {
  const MovementScreen({
    super.key,
    required this.auth,
    required this.productId,
    required this.unit,
  });
  final AuthController auth;
  final int productId;
  final String unit;
  @override
  State<MovementScreen> createState() => _MovementScreenState();
}

class _MovementScreenState extends State<MovementScreen> {
  late MovementController state;
  @override
  void initState() {
    super.initState();
    _createController();
  }

  void _createController() {
    state = MovementController(
      widget.auth,
      InventoryRepository(widget.auth.repository.client),
      widget.productId,
    );
    state.load();
  }

  @override
  void didUpdateWidget(covariant MovementScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.productId != widget.productId) {
      state.dispose();
      _createController();
    }
  }

  @override
  void dispose() {
    state.dispose();
    super.dispose();
  }

  Future<void> _customRange() async {
    var from = '', to = '';
    String? error;
    final range = await showDialog<MovementRange>(
      context: context,
      builder: (context) => StatefulBuilder(
        builder: (context, setDialogState) => AlertDialog(
          title: const Text('فترة الحركات'),
          content: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Text('تاريخ الأعمال بتوقيت الكويت؛ حتى 366 يوماً.'),
                TextFormField(
                  decoration: const InputDecoration(
                    labelText: 'من (yyyy-MM-dd)',
                  ),
                  textDirection: TextDirection.ltr,
                  onChanged: (value) => from = value,
                ),
                const SizedBox(height: 12),
                TextFormField(
                  decoration: const InputDecoration(
                    labelText: 'إلى (yyyy-MM-dd)',
                  ),
                  textDirection: TextDirection.ltr,
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
                    MovementRange.custom(from.trim(), to.trim()),
                  );
                } catch (_) {
                  setDialogState(
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
    if (mounted && range != null) state.setRange(range);
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('حركات المخزون')),
    body: ListenableBuilder(
      listenable: state,
      builder: (context, _) {
        if (!state.allowed) return const InventoryBlocked();
        return Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 1000),
            child: InventoryRows<InventoryMovement>(
              state: state,
              header: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const Text(
                    'الحركات الفعلية حسب تاريخ الأعمال بتوقيت الكويت، الأحدث أولاً.',
                  ),
                  const SizedBox(height: 12),
                  Wrap(
                    spacing: 12,
                    runSpacing: 12,
                    children: [
                      SizedBox(
                        width: 210,
                        child: DropdownButtonFormField<MovementPeriod>(
                          key: ValueKey(state.range),
                          initialValue: state.range.period,
                          isExpanded: true,
                          decoration: const InputDecoration(
                            labelText: 'الفترة',
                          ),
                          hint: const Text('فترة مخصصة'),
                          items: MovementPeriod.values
                              .map(
                                (period) => DropdownMenuItem(
                                  value: period,
                                  child: Text(period.label),
                                ),
                              )
                              .toList(),
                          onChanged: (value) {
                            if (value != null) {
                              state.setRange(MovementRange.preset(value));
                            }
                          },
                        ),
                      ),
                      OutlinedButton(
                        onPressed: _customRange,
                        child: const Text('تحديد تاريخين'),
                      ),
                      OutlinedButton.icon(
                        onPressed: state.loading ? null : state.load,
                        icon: const Icon(Icons.refresh),
                        label: const Text('تحديث الحركات'),
                      ),
                    ],
                  ),
                ],
              ),
              empty: 'لا توجد حركات مخزون لهذا المنتج',
              row: (m) => Container(
                padding: const EdgeInsets.symmetric(
                  vertical: 16,
                  horizontal: 8,
                ),
                decoration: BoxDecoration(
                  border: Border(
                    bottom: BorderSide(
                      color: Theme.of(context).colorScheme.outlineVariant,
                    ),
                  ),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Text(
                      m.typeName,
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    InventoryValue(m.date.replaceFirst('T', ' ')),
                    InventoryFacts([
                      (
                        'الحركة',
                        '${formatQuantity(m.quantity)} ${widget.unit}',
                      ),
                      (
                        'قبل الحركة',
                        '${formatQuantity(m.before)} ${widget.unit}',
                      ),
                      (
                        'بعد الحركة',
                        '${formatQuantity(m.after)} ${widget.unit}',
                      ),
                      if (state.access.cost && m.unitCost != null)
                        ('التكلفة التاريخية للوحدة', formatKwd(m.unitCost!)),
                    ]),
                  ],
                ),
              ),
            ),
          ),
        );
      },
    ),
  );
}
