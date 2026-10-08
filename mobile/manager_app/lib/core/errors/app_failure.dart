class AppFailure implements Exception {
  const AppFailure(this.code, this.message, {this.status});
  final String code;
  final String message;
  final int? status;
  static const malformed = AppFailure(
    'INVALID_RESPONSE',
    'تعذر قراءة استجابة الخدمة. حاول مرة أخرى.',
  );
  static const signedOut = AppFailure(
    'UNAUTHORIZED',
    'انتهت الجلسة. يرجى تسجيل الدخول مجدداً.',
    status: 401,
  );
  static const storage = AppFailure(
    'SECURE_STORAGE',
    'تعذر الوصول إلى التخزين الآمن. أعد المحاولة قبل تسجيل الدخول.',
  );

  factory AppFailure.http(int status, Object? data) {
    // Never display arbitrary server text: proxies and unexpected responses may contain internals.
    final code = data is Map ? data['code'] : null;
    final known = <String, String>{
      'INVALID_CREDENTIALS': 'اسم المستخدم أو كلمة المرور غير صحيحة.',
      'ACCOUNT_DISABLED': 'هذا الحساب معطّل. يرجى مراجعة المسؤول.',
      'NO_PERMISSIONS': 'لا توجد صلاحيات متاحة لهذا الحساب.',
      'ACCOUNT_LOCKED': 'الحساب مقفل مؤقتاً. يرجى المحاولة لاحقاً.',
      'PASSWORD_CHANGE_REQUIRED': 'يجب تغيير كلمة المرور قبل المتابعة.',
      'PASSWORD_REUSE': 'اختر كلمة مرور مختلفة عن كلمة المرور الحالية.',
      'SESSION_REVOKED': 'تم إنهاء هذه الجلسة. يرجى تسجيل الدخول مجدداً.',
    };
    final messages = <int, String>{
      400: 'تحقق من البيانات المدخلة وحاول مرة أخرى.',
      401: 'انتهت الجلسة أو بيانات الدخول غير صحيحة.',
      403: 'ليست لديك صلاحية لتنفيذ هذا الإجراء.',
      404: 'العنصر المطلوب غير موجود.',
      409: 'تعارض الطلب مع الحالة الحالية. حدّث البيانات وحاول مجدداً.',
      423: 'الحساب مقفل مؤقتاً. يرجى المحاولة لاحقاً.',
      429: 'طلبات كثيرة خلال وقت قصير. انتظر قليلاً ثم أعد المحاولة.',
    };
    return AppFailure(
      known.containsKey(code) ? code as String : 'HTTP_$status',
      known[code] ??
          messages[status] ??
          'الخدمة غير متاحة حالياً. يرجى المحاولة لاحقاً.',
      status: status,
    );
  }
  @override
  String toString() => 'AppFailure($code)';
}
