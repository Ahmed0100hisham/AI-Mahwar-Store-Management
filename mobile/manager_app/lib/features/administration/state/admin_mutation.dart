import 'package:flutter/foundation.dart';

import '../../../core/errors/app_failure.dart';
import '../../auth/state/auth_controller.dart';
import '../../final_features/data/feature_access.dart';
import '../data/admin_models.dart';

/// Never repeats an ambiguous write. The inherited client retries only rejected401s.
class AdminMutation extends ChangeNotifier {
  AdminMutation(this.auth) {
    _scope = scope;
    auth.addListener(_changed);
  }
  final AuthController auth;
  bool busy = false, uncertain = false, disposed = false, reread = false;
  AppFailure? error;
  AdminUser? result;
  int generation = 0;
  late String _scope;
  String get scope {
    final permissions = auth.user?.permissions.toList() ?? <String>[];
    permissions.sort();
    return '${auth.status}:${auth.user?.id}:${auth.user?.roleCode}:${auth.sessionEpoch}:${permissions.join(',')}';
  }

  FeatureAccess get access => FeatureAccess(auth.user);
  void _changed() {
    if (scope == _scope || disposed) return;
    _scope = scope;
    generation++;
    result = null;
    error = null;
    // Keep a pending write locked even if authorization changes mid-flight.
    notifyListeners();
  }

  Future<bool> run(
    bool Function(FeatureAccess) permit,
    Future<AdminUser> Function() operation,
  ) async {
    if (busy || uncertain || disposed) return false;
    if (auth.status != AuthStatus.authenticated || !permit(access)) {
      error = AppFailure.http(403, null);
      notifyListeners();
      return false;
    }
    final value = ++generation, selectedScope = scope;
    busy = true;
    error = null;
    result = null;
    reread = false;
    notifyListeners();
    try {
      final u = await operation();
      if (!disposed && value == generation && scope == selectedScope) {
        result = u;
        return true;
      }
      return false;
    } catch (e) {
      if (!disposed && value == generation && scope == selectedScope) {
        final f = e is AppFailure ? e : AppFailure.malformed;
        final local = {
          'USERNAME',
          'PROFILE',
          'PASSWORD_POLICY',
          'SELF_EDIT',
          'SELF_DISABLE',
          'SELF_RESET',
        };
        uncertain =
            !local.contains(f.code) && (f.status == null || f.status! >= 500);
        error = uncertain
            ? const AppFailure(
                'WRITE_UNCERTAIN',
                'لم يمكن تأكيد نتيجة العملية؛ ربما تم حفظها. أعد قراءة حالة المستخدم قبل أي محاولة جديدة. إعادة القراءة لا تثبت تغيير كلمة المرور.',
              )
            : f;
        notifyListeners();
        if (f.status == 401 || f.status == 403) await auth.checkSession();
      }
      return false;
    } finally {
      busy = false;
      if (!disposed) notifyListeners();
    }
  }

  void stateReread() {
    if (!disposed && uncertain) {
      reread = true;
      notifyListeners();
    }
  }

  void acknowledge() {
    if (disposed || busy || !reread) return;
    uncertain = false;
    error = null;
    result = null;
    reread = false;
    notifyListeners();
  }

  @override
  void dispose() {
    disposed = true;
    generation++;
    result = null;
    error = null;
    auth.removeListener(_changed);
    super.dispose();
  }
}
