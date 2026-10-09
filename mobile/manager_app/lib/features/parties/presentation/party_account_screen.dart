import 'package:flutter/material.dart';

import '../../auth/state/auth_controller.dart';
import '../../sales/presentation/sales_widgets.dart' show SalesIdentifier;
import '../data/party_access.dart';
import '../data/party_models.dart';
import '../data/party_repository.dart';
import '../state/party_controller.dart';
import 'party_widgets.dart';

class PartyAccountScreen extends StatefulWidget {
  const PartyAccountScreen({
    super.key,
    required this.auth,
    required this.kind,
    required this.id,
  });
  final AuthController auth;
  final PartyKind kind;
  final int id;
  @override
  State<PartyAccountScreen> createState() => _PartyAccountScreenState();
}

class _PartyAccountScreenState extends State<PartyAccountScreen> {
  late final state = PartyAccountController(
    widget.auth,
    PartyRepository(widget.auth.repository.client),
    widget.kind,
    widget.id,
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
    appBar: AppBar(title: Text('كشف حساب ${widget.kind.singular}')),
    body: ListenableBuilder(
      listenable: state,
      builder: (context, _) {
        if (!state.allowed || !state.access.financial) {
          return const PartyBlocked();
        }
        final account = state.account;
        return PartyRows<PartyEntry, PartyAccount>(
          state: state,
          empty: 'لا توجد حركات في الفترة المحددة.',
          row: (entry) => PartyEntryRow(entry),
          header: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                '${widget.kind.singular} #${state.id}',
                style: Theme.of(context).textTheme.titleLarge,
              ),
              const SizedBox(height: 16),
              PartyRangeControls(
                range: state.range,
                onChanged: state.setRange,
                onRefresh: state.load,
                loading: state.loading,
              ),
              const SizedBox(height: 12),
              Text(widget.kind.balanceNote),
              if (account != null) ...[
                const SizedBox(height: 16),
                const Text('الفترة المعروضة • تاريخ الأعمال'),
                SalesIdentifier(
                  '${account.range.from.iso} — ${account.range.to.iso}',
                ),
                PartyMoney('الرصيد المرحّل قبل بداية الفترة', account.opening),
                PartyMoney('إجمالي المدين • كامل الفترة', account.debit),
                PartyMoney('إجمالي الدائن • كامل الفترة', account.credit),
                PartyMoney('الرصيد الختامي • كامل الفترة', account.closing),
                const Text(
                  'الأرصدة والإجماليات من دفتر الحساب. الرصيد بعد الحركة يشمل التاريخ السابق، وليس رصيد الصفحة. تحديث البيانات يجدد إجماليات الفترة.',
                ),
              ],
            ],
          ),
        );
      },
    ),
  );
}
