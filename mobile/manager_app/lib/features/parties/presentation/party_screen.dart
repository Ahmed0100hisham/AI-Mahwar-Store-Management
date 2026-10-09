import 'package:flutter/material.dart';

import '../../auth/state/auth_controller.dart';
import '../../sales/presentation/sales_widgets.dart' show SalesIdentifier;
import '../data/party_access.dart';
import '../data/party_models.dart';
import '../data/party_repository.dart';
import '../state/party_controller.dart';
import 'party_detail_screen.dart';
import 'party_widgets.dart';

class PartyScreen extends StatefulWidget {
  const PartyScreen({
    super.key,
    required this.auth,
    required this.kind,
    this.outstanding = false,
  });
  final AuthController auth;
  final PartyKind kind;
  final bool outstanding;
  @override
  State<PartyScreen> createState() => _PartyScreenState();
}

class _PartyScreenState extends State<PartyScreen> {
  late final state = PartyListController(
    widget.auth,
    PartyRepository(widget.auth.repository.client),
    widget.kind,
    outstanding: widget.outstanding,
  );
  final search = TextEditingController();
  @override
  void initState() {
    super.initState();
    state.load();
  }

  @override
  void dispose() {
    state.dispose();
    search.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: state,
    builder: (context, _) {
      if (!state.allowed) return const PartyBlocked();
      final kind = state.kind;
      return PartyRows<PartyProfile, PartyListing>(
        state: state,
        empty: state.search.isNotEmpty
            ? 'لا توجد نتائج للبحث.'
            : state.mode == PartyMode.outstanding
            ? 'لا توجد أرصدة مستحقة ضمن النتائج.'
            : kind == PartyKind.customer
            ? 'لا يوجد عملاء.'
            : 'لا يوجد موردون.',
        header: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(kind.title, style: Theme.of(context).textTheme.headlineSmall),
            const SizedBox(height: 16),
            TextField(
              controller: search,
              maxLength: 100,
              onChanged: state.setSearch,
              decoration: InputDecoration(
                labelText: 'البحث بالاسم أو الكود أو الهاتف أو المنطقة',
                counterText: '',
                suffixIcon: IconButton(
                  tooltip: 'مسح البحث',
                  onPressed: () {
                    search.clear();
                    state.setSearch('', immediate: true);
                  },
                  icon: const Icon(Icons.clear),
                ),
              ),
            ),
            const SizedBox(height: 12),
            Wrap(
              spacing: 12,
              runSpacing: 12,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                SizedBox(
                  width: 210,
                  child: DropdownButtonFormField<PartySort>(
                    key: ValueKey(state.sort),
                    initialValue: state.sort,
                    isExpanded: true,
                    decoration: const InputDecoration(labelText: 'الترتيب'),
                    items: PartySort.values
                        .where((v) => !v.financial || state.access.financial)
                        .map(
                          (v) =>
                              DropdownMenuItem(value: v, child: Text(v.label)),
                        )
                        .toList(),
                    onChanged: (v) {
                      if (v != null) state.setSort(v);
                    },
                  ),
                ),
                if (state.access.financial)
                  OutlinedButton(
                    onPressed: () => state.setMode(
                      state.mode == PartyMode.all
                          ? PartyMode.outstanding
                          : PartyMode.all,
                    ),
                    child: Text(
                      state.mode == PartyMode.all
                          ? kind.debtTitle
                          : 'عرض الجميع',
                    ),
                  ),
                OutlinedButton.icon(
                  onPressed: state.loading ? null : state.load,
                  icon: const Icon(Icons.refresh),
                  label: const Text('تحديث البيانات'),
                ),
              ],
            ),
            if (state.access.financial) ...[
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 12),
                child: Text(
                  kind.balanceNote,
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ),
              if (state.mode == PartyMode.outstanding &&
                  state.totalOutstanding != null)
                PartyMoney(
                  'إجمالي ${kind.debtTitle} • كل نتائج البحث',
                  state.totalOutstanding!,
                ),
              if (state.mode == PartyMode.outstanding)
                const Text(
                  'الأرصدة الموجبة من دفتر الحساب، وتشمل الأطراف غير النشطة. الإجمالي يشمل كل المطابقات، وليس الصفحة فقط.',
                ),
            ],
          ],
        ),
        row: (party) => Column(
          children: [
            const Divider(height: 1),
            InkWell(
              onTap: () {
                if (state.allowed) {
                  Navigator.push(
                    context,
                    MaterialPageRoute<void>(
                      builder: (_) => PartyDetailScreen(
                        auth: widget.auth,
                        kind: kind,
                        id: party.id,
                      ),
                    ),
                  );
                }
              },
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Text(
                      party.name,
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    SalesIdentifier(
                      party.code,
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                    Wrap(
                      spacing: 16,
                      runSpacing: 4,
                      children: [
                        Text(party.active ? 'نشط' : 'غير نشط'),
                        if (party.phone != null && party.phone!.isNotEmpty)
                          SalesIdentifier(
                            party.phone!,
                            style: Theme.of(context).textTheme.bodyMedium,
                          ),
                        if (party.area != null && party.area!.isNotEmpty)
                          Text(party.area!),
                      ],
                    ),
                    if (state.access.financial && party.balance != null)
                      PartyMoney(
                        state.mode == PartyMode.outstanding
                            ? 'الرصيد المستحق من دفتر الحساب'
                            : 'الرصيد الحالي',
                        party.balance!,
                      ),
                    Text(
                      'عرض بيانات ${kind.singular}',
                      style: TextStyle(
                        color: Theme.of(context).colorScheme.primary,
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      );
    },
  );
}
