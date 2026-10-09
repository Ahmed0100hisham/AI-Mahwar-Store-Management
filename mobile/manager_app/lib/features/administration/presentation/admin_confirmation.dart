import 'package:flutter/material.dart';

import '../../auth/state/auth_controller.dart';

/// A pending identity confirmation loses its contents and approval on scope loss.
Future<bool> confirmAdminAction(
  BuildContext context,
  AuthController auth,
  String title,
  String message,
) async {
  String scope() {
    final codes = auth.user?.permissions.toList() ?? <String>[];
    codes.sort();
    return '${auth.status}:${auth.sessionEpoch}:${auth.user?.id}:${auth.user?.roleCode}:${codes.join(',')}';
  }

  final selected = scope();
  return await showDialog<bool>(
        context: context,
        builder: (context) => ListenableBuilder(
          listenable: auth,
          builder: (context, _) {
            final valid =
                auth.status == AuthStatus.authenticated && scope() == selected;
            return AlertDialog(
              title: Text(title),
              content: Text(
                valid ? message : 'تغيرت الجلسة أو الصلاحيات. ألغِ التأكيد وأعد فتح المستخدم.',
              ),
              actions: [
                TextButton(
                  onPressed: () => Navigator.pop(context, false),
                  child: const Text('إلغاء'),
                ),
                FilledButton(
                  onPressed: valid ? () => Navigator.pop(context, true) : null,
                  child: const Text('تأكيد'),
                ),
              ],
            );
          },
        ),
      ) ??
      false;
}
