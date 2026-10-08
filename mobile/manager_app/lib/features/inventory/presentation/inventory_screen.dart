import 'package:flutter/material.dart';

import '../../../core/utils/money.dart';
import '../../auth/state/auth_controller.dart';
import '../data/inventory_models.dart';
import '../data/inventory_repository.dart';
import '../state/inventory_controller.dart';
import 'inventory_widgets.dart';
import 'product_detail_screen.dart';

class InventoryScreen extends StatefulWidget {
  const InventoryScreen({super.key, required this.auth, this.lowStock = false});
  final AuthController auth;
  final bool lowStock;
  @override
  State<InventoryScreen> createState() => _InventoryScreenState();
}

class _InventoryScreenState extends State<InventoryScreen> {
  late final InventoryListController state;
  final search = TextEditingController();
  @override
  void initState() {
    super.initState();
    state = InventoryListController(
      widget.auth,
      InventoryRepository(widget.auth.repository.client),
      lowStock: widget.lowStock,
    );
    state.load();
  }

  @override
  void dispose() {
    search.dispose();
    state.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: state,
    builder: (context, _) {
      if (!state.allowed) return const InventoryBlocked();
      final access = state.access;
      return Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 1000),
          child: InventoryRows<InventoryProduct>(
            state: state,
            header: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(
                  'المنتجات والمخزون',
                  style: Theme.of(context).textTheme.headlineSmall,
                ),
                const SizedBox(height: 12),
                const Text('بحث بالاسم أو الكود أو الباركود'),
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
                  onChanged: (text) => state.setSearch(text),
                  onSubmitted: (text) => state.setSearch(text, immediate: true),
                ),
                Wrap(
                  spacing: 12,
                  runSpacing: 12,
                  crossAxisAlignment: WrapCrossAlignment.center,
                  children: [
                    if (access.products && access.inventory)
                      SizedBox(
                        width: 210,
                        child: DropdownButtonFormField<InventoryMode>(
                          key: ValueKey(state.mode),
                          initialValue: state.mode,
                          isExpanded: true,
                          decoration: const InputDecoration(labelText: 'العرض'),
                          items: const [
                            DropdownMenuItem(
                              value: InventoryMode.all,
                              child: Text('كل المنتجات'),
                            ),
                            DropdownMenuItem(
                              value: InventoryMode.low,
                              child: Text('مخزون منخفض'),
                            ),
                          ],
                          onChanged: (value) {
                            if (value != null) state.setMode(value);
                          },
                        ),
                      ),
                    SizedBox(
                      width: 210,
                      child: DropdownButtonFormField<InventorySort>(
                        key: ValueKey(state.sort),
                        initialValue: state.sort,
                        isExpanded: true,
                        decoration: const InputDecoration(labelText: 'الترتيب'),
                        items: InventorySort.values
                            .map(
                              (sort) => DropdownMenuItem(
                                value: sort,
                                child: Text(sort.label),
                              ),
                            )
                            .toList(),
                        onChanged: (value) {
                          if (value != null) state.setSort(value);
                        },
                      ),
                    ),
                    OutlinedButton.icon(
                      onPressed: state.loading ? null : state.load,
                      icon: const Icon(Icons.refresh),
                      label: const Text('تحديث المنتجات'),
                    ),
                  ],
                ),
                if (access.inactive && state.mode == InventoryMode.all)
                  CheckboxListTile(
                    contentPadding: EdgeInsets.zero,
                    title: const Text('إظهار غير النشط أيضاً'),
                    value: state.includeInactive,
                    onChanged: (value) => state.setInactive(value ?? false),
                  ),
              ],
            ),
            empty: state.search.isNotEmpty
                ? 'لا توجد نتائج مطابقة'
                : state.mode == InventoryMode.low
                ? 'لا توجد منتجات منخفضة المخزون'
                : 'لا توجد منتجات لعرضها',
            row: (product) => Semantics(
              button: access.products,
              child: InkWell(
                onTap: access.products
                    ? () => Navigator.push(
                        context,
                        MaterialPageRoute<void>(
                          builder: (_) => ProductDetailScreen(
                            auth: widget.auth,
                            id: product.id,
                          ),
                        ),
                      )
                    : null,
                child: Container(
                  padding: const EdgeInsets.symmetric(
                    vertical: 14,
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
                        product.name,
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                      InventoryValue(
                        product.code,
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                      InventoryFacts([
                        (
                          'الكمية',
                          '${formatQuantity(product.quantity)} ${product.unit}',
                        ),
                        ('حالة المخزون', product.stockLabel),
                        if (product.salePrice != null)
                          ('سعر البيع', formatKwd(product.salePrice!)),
                        if (access.cost && product.cost != null)
                          ('تكلفة الشراء للوحدة', formatKwd(product.cost!)),
                      ]),
                      if (access.products)
                        const Text(
                          'عرض التفاصيل',
                          style: TextStyle(
                            decoration: TextDecoration.underline,
                          ),
                        ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ),
      );
    },
  );
}
