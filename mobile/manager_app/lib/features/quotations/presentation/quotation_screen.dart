import 'package:flutter/material.dart';

import '../../../core/utils/money.dart';
import '../../auth/state/auth_controller.dart';
import '../../final_features/data/feature_access.dart';
import '../../final_features/presentation/feature_widgets.dart';
import '../../final_features/state/feature_state.dart';
import '../../parties/presentation/party_widgets.dart' show PartyMoney;
import '../../sales/data/sales_models.dart'
    show invoiceStateLabel, priceTypeLabel;
import '../../sales/presentation/invoice_detail_screen.dart';
import '../../sales/presentation/sales_widgets.dart' show SalesIdentifier;
import '../../inventory/presentation/inventory_widgets.dart'
    show InventoryValue;
import '../data/quotation_models.dart';
import '../data/quotation_repository.dart';

class QuotationScreen extends StatefulWidget {
  const QuotationScreen({super.key, required this.auth});
  final AuthController auth;
  @override
  State<QuotationScreen> createState() => _QuotationScreenState();
}

class _QuotationScreenState extends State<QuotationScreen> {
  final query = QuotationQuery();
  late final repository = QuotationRepository(widget.auth.repository.client);
  late final state = FeaturePaged<Quotation>(
    widget.auth,
    permit: (a) => a.quotations,
    fetch: (page, size) =>
        repository.list(FeatureAccess(widget.auth.user), query, page, size),
    identity: (q) => q.id,
  );
  final search = TextEditingController(), customer = TextEditingController();
  String? filterError;
  @override
  void initState() {
    super.initState();
    state.load();
  }

  @override
  void dispose() {
    state.dispose();
    search.dispose();
    customer.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: state,
    builder: (context, _) => FeatureRows(
      state: state,
      header: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(
            'عروض الأسعار',
            style: Theme.of(context).textTheme.headlineSmall,
          ),
          const Text('للعرض فقط؛ الحالة والصلاحية والتحويل بحسب الخادم.'),
          TextField(
            controller: search,
            maxLength: 100,
            decoration: InputDecoration(
              labelText: 'البحث بالرقم أو العميل أو الهاتف',
              counterText: '',
              suffixIcon: IconButton(
                tooltip: 'مسح البحث',
                icon: const Icon(Icons.clear),
                onPressed: () {
                  search.clear();
                  query.search = '';
                  state.queryChanged();
                },
              ),
            ),
            onChanged: (v) {
              query.search = v;
              state.queryChanged(debounce: true);
            },
            onSubmitted: (v) {
              query.search = v;
              state.queryChanged();
            },
          ),
          FeatureRangeControls(
            range: query.range,
            changed: (v) {
              query.range = v;
              state.queryChanged();
            },
            refresh: state.load,
            loading: state.loading,
          ),
          Wrap(
            spacing: 12,
            runSpacing: 12,
            children: [
              FeatureChoice<QuotationStatus?>(
                label: 'الحالة',
                value: query.status,
                choices: {
                  null: 'كل الحالات',
                  for (final v in QuotationStatus.values) v: v.label,
                },
                onChanged: (v) {
                  query.status = v;
                  state.queryChanged();
                },
              ),
              FeatureChoice<bool?>(
                label: 'تجاوز تاريخ الصلاحية',
                value: query.pastValidity,
                choices: const {
                  null: 'الكل',
                  true: 'تجاوز الصلاحية',
                  false: 'لم يتجاوز الصلاحية',
                },
                onChanged: (v) {
                  query.pastValidity = v;
                  state.queryChanged();
                },
              ),
              FeatureChoice<QuotationSort>(
                label: 'الترتيب',
                value: query.sort,
                choices: {for (final v in QuotationSort.values) v: v.label},
                onChanged: (v) {
                  if (v != null) {
                    query.sort = v;
                    state.queryChanged();
                  }
                },
              ),
            ],
          ),
          TextField(
            controller: customer,
            keyboardType: TextInputType.number,
            decoration: const InputDecoration(
              labelText: 'معرّف العميل المسجّل (اختياري)',
            ),
            onSubmitted: (v) {
              final id = v.trim().isEmpty ? null : int.tryParse(v.trim());
              if (v.trim().isNotEmpty &&
                  (id == null || id < 1 || id > 2147483647)) {
                setState(() => filterError = 'أدخل معرّف عميل صحيحاً.');
                return;
              }
              setState(() => filterError = null);
              query.customerId = id;
              state.queryChanged();
            },
          ),
          if (filterError != null) Text(filterError!),
        ],
      ),
      row: (Quotation q) => Card(
        child: ListTile(
          title: SalesIdentifier(q.number),
          subtitle: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(quotationLabel(q.status)),
              Text(q.customerName ?? q.prospectName ?? 'اسم العميل غير متاح'),
              SalesIdentifier(q.date),
              PartyMoney('الإجمالي', q.total),
              Text(
                q.pastValidity
                    ? 'تجاوز تاريخ الصلاحية'
                    : 'لم يتجاوز تاريخ الصلاحية',
              ),
            ],
          ),
          trailing: const Icon(Icons.chevron_left),
          onTap: () => Navigator.push(
            context,
            MaterialPageRoute<void>(
              builder: (_) =>
                  QuotationDetailScreen(auth: widget.auth, id: q.id),
            ),
          ),
        ),
      ),
    ),
  );
}

class QuotationDetailScreen extends StatefulWidget {
  const QuotationDetailScreen({
    super.key,
    required this.auth,
    required this.id,
  });
  final AuthController auth;
  final int id;
  @override
  State<QuotationDetailScreen> createState() => _QuotationDetailScreenState();
}

class _QuotationDetailScreenState extends State<QuotationDetailScreen> {
  late final repository = QuotationRepository(widget.auth.repository.client);
  late final state = FeaturePaged<QuotationLine>(
    widget.auth,
    permit: (a) => a.quotations,
    fetch: (page, size) => repository.detail(
      FeatureAccess(widget.auth.user),
      widget.id,
      page,
      size,
    ),
    identity: (v) => v.id,
  );
  @override
  void initState() {
    super.initState();
    state.load();
  }

  @override
  void dispose() {
    state.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('تفاصيل عرض السعر')),
    body: ListenableBuilder(
      listenable: state,
      builder: (context, _) {
        final q = state.data?.header as Quotation?;
        return FeatureRows(
          state: state,
          header: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              OutlinedButton(
                onPressed: state.loading ? null : state.load,
                child: const Text('تحديث البيانات'),
              ),
              if (q != null) ...[
                SalesIdentifier(q.number),
                Text(quotationLabel(q.status)),
                SalesIdentifier(q.date),
                Text('منشئ العرض: ${q.creatorName}'),
                Text('نوع السعر: ${priceTypeLabel(q.priceType)}'),
                if (q.customerId != null)
                  Text(
                    'العميل المسجّل: ${q.customerName ?? 'غير متاح'} • ${q.customerCode ?? q.customerId}',
                  ),
                if (q.customerPhone != null) SalesIdentifier(q.customerPhone!),
                if (q.prospectName != null)
                  Text('الاسم الوارد بالعرض: ${q.prospectName}'),
                if (q.prospectPhone != null) SalesIdentifier(q.prospectPhone!),
                Text('تاريخ أعمال الخادم: ${q.businessDate.iso}'),
                Text(
                  q.validUntil == null
                      ? 'لا يوجد تاريخ انتهاء محدد'
                      : 'صالح حتى: ${q.validUntil!.iso}',
                ),
                Text(
                  q.pastValidity
                      ? 'تجاوز تاريخ الصلاحية؛ الحالة المسجّلة أعلاه مستقلة.'
                      : 'لم يتجاوز تاريخ الصلاحية.',
                ),
                if (q.sentAt != null) Text('الإرسال المسجّل: ${q.sentAt}'),
                if (q.decidedAt != null) Text('القرار المسجّل: ${q.decidedAt}'),
                PartyMoney('المجموع الفرعي', q.subtotal),
                PartyMoney('الخصم', q.discount),
                PartyMoney('الإجمالي', q.total),
                if (FeatureAccess(widget.auth.user).saleLinks &&
                    q.linkedId != null) ...[
                  const Text(
                    'الفاتورة المرتبطة فعلياً؛ وجود الرابط لا يثبت ترحيلها أو اكتمال التحويل.',
                  ),
                  Text(
                    '${q.linkedNumber} • ${invoiceStateLabel(q.linkedStatus!)}',
                  ),
                  OutlinedButton(
                    onPressed: () => Navigator.push(
                      context,
                      MaterialPageRoute<void>(
                        builder: (_) => InvoiceDetailScreen(
                          auth: widget.auth,
                          id: q.linkedId!,
                        ),
                      ),
                    ),
                    child: const Text('عرض الفاتورة المرتبطة'),
                  ),
                ],
                const Text(
                  'بنود العرض؛ الأسماء الحالية والقيم المسجّلة في المستند.',
                ),
              ],
            ],
          ),
          row: (QuotationLine line) => Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text(line.name),
                  SalesIdentifier(line.code),
                  InventoryValue(
                    '${formatQuantity(line.quantity)} ${line.unit}',
                  ),
                  PartyMoney('سعر الوحدة', line.unitPrice),
                  PartyMoney('خصم البند', line.discount),
                  PartyMoney('إجمالي البند المسجّل', line.total),
                ],
              ),
            ),
          ),
        );
      },
    ),
  );
}
