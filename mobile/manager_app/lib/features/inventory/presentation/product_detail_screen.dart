import 'package:flutter/material.dart';

import '../../../core/utils/money.dart';
import '../../auth/state/auth_controller.dart';
import '../data/inventory_repository.dart';
import '../state/inventory_controller.dart';
import 'inventory_widgets.dart';
import 'movement_screen.dart';

class ProductDetailScreen extends StatefulWidget {
  const ProductDetailScreen({super.key, required this.auth, required this.id});
  final AuthController auth;
  final int id;
  @override
  State<ProductDetailScreen> createState() => _ProductDetailScreenState();
}

class _ProductDetailScreenState extends State<ProductDetailScreen> {
  late final ProductDetailController state;
  @override
  void initState() {
    super.initState();
    state = ProductDetailController(
      widget.auth,
      InventoryRepository(widget.auth.repository.client),
      widget.id,
    );
    state.load();
  }

  @override
  void didUpdateWidget(covariant ProductDetailScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.id != widget.id) state.selectProduct(widget.id);
  }

  @override
  void dispose() {
    state.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('تفاصيل المنتج')),
    body: ListenableBuilder(
      listenable: state,
      builder: (context, _) {
        if (!state.allowed) return const InventoryBlocked();
        final p = state.product;
        return RefreshIndicator(
          onRefresh: state.load,
          child: SingleChildScrollView(
            physics: const AlwaysScrollableScrollPhysics(),
            padding: const EdgeInsets.all(16),
            child: Center(
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 800),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    if (state.loading)
                      const LinearProgressIndicator(
                        semanticsLabel: 'جارٍ تحميل تفاصيل المنتج',
                      ),
                    if (state.error != null) ...[
                      InventoryError(state.error!, onRetry: state.load),
                      if (p != null)
                        const Text('تعذر التحديث. هذه بيانات آخر تحميل ناجح.'),
                    ],
                    if (p != null) ...[
                      Text(
                        p.name,
                        style: Theme.of(context).textTheme.headlineSmall,
                      ),
                      if (p.nameEn != null && p.nameEn!.isNotEmpty)
                        Text(p.nameEn!),
                      const SizedBox(height: 16),
                      InventoryFacts([
                        ('كود المنتج', p.code),
                        if (p.barcode != null && p.barcode!.isNotEmpty)
                          ('الباركود', p.barcode!),
                        ('الوحدة', p.unit),
                        if (p.category != null) ('التصنيف', p.category!),
                        if (p.brand != null) ('العلامة', p.brand!),
                      ]),
                      const Divider(height: 32),
                      Text(
                        'المخزون الحالي',
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                      InventoryFacts([
                        ('الكمية', '${formatQuantity(p.quantity)} ${p.unit}'),
                        (
                          'الحد الأدنى',
                          '${formatQuantity(p.minimum)} ${p.unit}',
                        ),
                        ('حالة المخزون', p.stockLabel),
                        ('حالة المنتج', p.active ? 'نشط' : 'غير نشط'),
                      ]),
                      const Divider(height: 32),
                      InventoryFacts([
                        ('سعر البيع', formatKwd(p.salePrice!)),
                        if (state.access.cost && p.cost != null)
                          ('تكلفة الشراء للوحدة', formatKwd(p.cost!)),
                      ]),
                      if (state.access.movements)
                        Padding(
                          padding: const EdgeInsets.only(top: 20),
                          child: OutlinedButton.icon(
                            onPressed: () => Navigator.push(
                              context,
                              MaterialPageRoute<void>(
                                builder: (_) => MovementScreen(
                                  auth: widget.auth,
                                  productId: p.id,
                                  unit: p.unit,
                                ),
                              ),
                            ),
                            icon: const Icon(Icons.history),
                            label: const Text('حركات المخزون'),
                          ),
                        ),
                    ],
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
