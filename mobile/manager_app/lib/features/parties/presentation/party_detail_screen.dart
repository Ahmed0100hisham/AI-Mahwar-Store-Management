import 'package:flutter/material.dart';

import '../../auth/state/auth_controller.dart';
import '../../inventory/presentation/inventory_widgets.dart'
    show InventoryError;
import '../../sales/presentation/sales_widgets.dart' show SalesIdentifier;
import '../data/party_access.dart';
import '../data/party_repository.dart';
import '../state/party_controller.dart';
import 'party_account_screen.dart';
import 'party_widgets.dart';

class PartyDetailScreen extends StatefulWidget {
  const PartyDetailScreen({
    super.key,
    required this.auth,
    required this.kind,
    required this.id,
  });
  final AuthController auth;
  final PartyKind kind;
  final int id;
  @override
  State<PartyDetailScreen> createState() => _PartyDetailScreenState();
}

class _PartyDetailScreenState extends State<PartyDetailScreen> {
  late final state = PartyDetailController(
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
    appBar: AppBar(title: Text('بيانات ${widget.kind.singular}')),
    body: ListenableBuilder(
      listenable: state,
      builder: (context, _) {
        if (!state.allowed) return const PartyBlocked();
        final party = state.profile;
        return RefreshIndicator(
          onRefresh: state.load,
          child: ListView(
            physics: const AlwaysScrollableScrollPhysics(),
            padding: const EdgeInsets.all(16),
            children: [
              if (state.loading)
                const LinearProgressIndicator(
                  semanticsLabel: 'جارٍ تحميل بيانات الطرف',
                ),
              OutlinedButton.icon(
                onPressed: state.loading ? null : state.load,
                icon: const Icon(Icons.refresh),
                label: const Text('تحديث البيانات'),
              ),
              if (state.error != null)
                InventoryError(state.error!, onRetry: state.load),
              if (party != null) ...[
                if (state.error != null)
                  const Text('تعذر التحديث. هذه بيانات آخر تحميل ناجح.'),
                Text(
                  party.name,
                  style: Theme.of(context).textTheme.headlineSmall,
                ),
                const SizedBox(height: 16),
                const Text('الكود'),
                SalesIdentifier(party.code),
                const SizedBox(height: 12),
                Text('الحالة: ${party.active ? 'نشط' : 'غير نشط'}'),
                if (party.phone != null && party.phone!.isNotEmpty) ...[
                  const SizedBox(height: 12),
                  const Text('الهاتف'),
                  SalesIdentifier(party.phone!),
                ],
                if (party.area != null && party.area!.isNotEmpty) ...[
                  const SizedBox(height: 12),
                  const Text('المنطقة'),
                  Text(party.area!),
                ],
                if (state.access.financial) ...[
                  if (party.balance != null)
                    PartyMoney('الرصيد الحالي', party.balance!),
                  Text(widget.kind.balanceNote),
                  const SizedBox(height: 20),
                  OutlinedButton(
                    onPressed: () {
                      if (state.allowed && state.access.financial) {
                        Navigator.push(
                          context,
                          MaterialPageRoute<void>(
                            builder: (_) => PartyAccountScreen(
                              auth: widget.auth,
                              kind: widget.kind,
                              id: state.id,
                            ),
                          ),
                        );
                      }
                    },
                    child: const Text('عرض كشف الحساب'),
                  ),
                ],
              ],
            ],
          ),
        );
      },
    ),
  );
}
