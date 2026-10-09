import '../../../core/errors/app_failure.dart';
import '../../../core/network/api_client.dart';
import '../../auth/data/auth_models.dart';
import '../../dashboard/data/dashboard_models.dart';
import '../../inventory/data/inventory_models.dart';
import '../../sales/data/sales_models.dart';
import '../../final_features/data/feature_access.dart';
import '../../final_features/data/feature_query.dart';
import '../../final_features/state/feature_state.dart';

// Exactly AuditLogDao constants + the three released API session events.
const auditActions = <String>{
  'LOGIN',
  'LOGIN_FAILED',
  'LOGOUT',
  'INSERT',
  'UPDATE',
  'DELETE',
  'ACTIVATE',
  'DEACTIVATE',
  'STOCK_ADJUSTMENT',
  'CREATE_PURCHASE',
  'POST_PURCHASE',
  'CANCEL_PURCHASE',
  'CREATE_SALE',
  'POST_SALE',
  'CANCEL_SALE',
  'PRICE_OVERRIDE',
  'CREDIT_OVERRIDE',
  'CUSTOMER_PAYMENT_CREATED',
  'SUPPLIER_PAYMENT_CREATED',
  'EXPENSE_CREATED',
  'CASH_DEPOSIT',
  'CASH_WITHDRAWAL',
  'SALE_RETURN_CREATED',
  'PURCHASE_RETURN_CREATED',
  'QUOTATION_CREATED',
  'QUOTATION_UPDATED',
  'QUOTATION_DELETED',
  'QUOTATION_SENT',
  'QUOTATION_REOPENED',
  'QUOTATION_ACCEPTED',
  'QUOTATION_REJECTED',
  'QUOTATION_EXPIRED',
  'QUOTATION_CONVERSION_STARTED',
  'QUOTATION_CONVERTED',
  'QUOTATION_PRICE_OVERRIDE',
  'QUOTATION_DISCOUNT',
  'COMPANY_SETTINGS_UPDATED',
  'SYSTEM_SETTINGS_UPDATED',
  'LOGO_CHANGED',
  'USER_CREATED',
  'USER_UPDATED',
  'USER_ENABLED',
  'USER_DISABLED',
  'USER_ROLE_CHANGED',
  'PASSWORD_CHANGED',
  'PASSWORD_RESET',
  'ACCOUNT_LOCKED',
  'ACCOUNT_UNLOCKED',
  'BACKUP_STARTED',
  'BACKUP_COMPLETED',
  'BACKUP_FAILED',
  'BACKUP_VERIFIED',
  'BACKUP_VERIFY_FAILED',
  'RESTORE_REQUESTED',
  'PRE_RESTORE_BACKUP_CREATED',
  'RESTORE_COMPLETED',
  'RESTORE_FAILED',
  'API_LOGOUT_ALL',
  'API_SESSION_REVOKED',
  'API_REFRESH_REUSE',
};
const auditCategories = <String>{
  'Users',
  'Products',
  'Customers',
  'Suppliers',
  'Sales',
  'Purchases',
  'Expenses',
  'Cash_Transactions',
  'Sale_Returns',
  'Purchase_Returns',
  'Quotations',
  'Company_Settings',
  'System_Settings',
};

class AuditEvent {
  AuditEvent.fromJson(Object? raw) {
    final j = jsonObject(raw);
    id = count(j, 'id');
    if (id < 1) throw AppFailure.malformed;
    timestamp = businessTimestamp(j, 'timestamp');
    userId = j['userId'] == null ? null : documentId(j, 'userId');
    username = nullableText(j, 'username');
    fullName = nullableText(j, 'fullName');
    final event = requiredString(j, 'action'),
        table = requiredString(j, 'category');
    action = auditActions.contains(event) ? event : 'OTHER';
    category = auditCategories.contains(table) ? table : 'OTHER';
    // No legacy payload, description, record values or machine metadata retained.
  }
  late final int id;
  late final int? userId;
  late final String timestamp, action, category;
  late final String? username, fullName;
  @override
  String toString() => 'AuditEvent([PRIVATE])';
}

class AuditQuery {
  MovementRange range = const MovementRange.preset(MovementPeriod.today);
  int? userId;
  String? action, category;
}

class AuditRepository {
  AuditRepository(this.client);
  final ApiClient client;
  Future<FeaturePage<AuditEvent>> list(
    FeatureAccess access,
    AuditQuery query,
    int page,
    int size,
  ) async {
    requireFeature(access.audit);
    if (query.userId != null) featureId(query.userId!);
    if ((query.action != null && !auditActions.contains(query.action)) ||
        (query.category != null && !auditCategories.contains(query.category))) {
      throw AppFailure.malformed;
    }
    final entries = InventoryPage<AuditEvent>.fromJson(
      await client.send(
        'GET',
        featurePath('manager/audit', {
          ...query.range.query,
          ...featurePage(page, size),
          'sort': 'date,desc',
          if (query.userId != null) 'userId': '${query.userId}',
          if (query.action != null) 'action': query.action!,
          if (query.category != null) 'category': query.category!,
        }),
      ),
      AuditEvent.fromJson,
      expectedPage: page,
      expectedSize: size,
    );
    if (entries.items.map((v) => v.id).toSet().length != entries.items.length) {
      throw AppFailure.malformed;
    }
    return FeaturePage(entries);
  }
}
