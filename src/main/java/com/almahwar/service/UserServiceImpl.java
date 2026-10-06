package com.almahwar.service;

import com.almahwar.dao.AuditLogDao;
import com.almahwar.dao.DataAccessException;
import com.almahwar.dao.RoleDao;
import com.almahwar.dao.TransactionManager;
import com.almahwar.dao.UserDao;
import com.almahwar.model.Permission;
import com.almahwar.model.Role;
import com.almahwar.model.User;
import com.almahwar.model.UserAccount;
import com.almahwar.model.UserAccount.Changes;
import com.almahwar.model.UserAccount.NewUser;
import com.almahwar.model.UserAccount.PermissionRow;
import com.almahwar.util.PasswordHasher;
import com.almahwar.util.PhoneNumbers;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.almahwar.service.Validation.trimToNull;

/** {@link UserService} on SQL Server. */
public class UserServiceImpl implements UserService {

    static final String TABLE = "Users";

    /** The roles a user may be given (the seeded MANAGER role has no permissions and is not offered). */
    static final List<String> ASSIGNABLE_ROLES = List.of(Role.ADMIN, Role.ACCOUNTANT, Role.CASHIER, Role.STOREKEEPER);

    private final UserDao userDao;
    private final RoleDao roleDao;
    private final AuditLogDao auditLogDao;
    private final SecurityContext security;

    public UserServiceImpl(UserDao userDao, RoleDao roleDao, AuditLogDao auditLogDao, SecurityContext security) {
        this.userDao = userDao;
        this.roleDao = roleDao;
        this.auditLogDao = auditLogDao;
        this.security = security;
    }

    // ======================= Reading =======================

    @Override
    public Map<String, String> roles() {
        security.requirePermission(Permission.USERS_VIEW);
        Map<String, String> map = new LinkedHashMap<>();
        for (String code : ASSIGNABLE_ROLES) {
            roleDao.findByCode(code).ifPresent(r -> map.put(code, r.getRoleName()));
        }
        return map;
    }

    @Override
    public List<UserAccount> search(String text, String roleCode, Boolean active) {
        security.requirePermission(Permission.USERS_VIEW);
        return userDao.search(trimToNull(text), roleCode, active).stream().map(UserAccount::of).toList();
    }

    @Override
    public UserAccount findById(int userId) {
        security.requirePermission(Permission.USERS_VIEW);
        return userDao.findById(userId).map(UserAccount::of)
                .orElseThrow(() -> new ValidationException(USERNAME, "المستخدم غير موجود."));
    }

    // ======================= Creating =======================

    @Override
    public UserAccount create(NewUser n, char[] password, char[] confirm) {
        try {
            security.requirePermission(Permission.USERS_CREATE);
            int adminId = security.currentUser().getUserId();
            Validation v = new Validation();
            String username = CredentialPolicy.normalizeUsername(n.username());
            try {
                CredentialPolicy.validateUsername(username);
            } catch (IllegalArgumentException e) {
                v.error(USERNAME, e.getMessage());
            }
            String fullName = trimToNull(n.fullName());
            String phone = PhoneNumbers.normalize(n.phone());
            String email = trimToNull(n.email());
            profileRules(v, fullName, phone, email);
            Role role = role(v, n.roleCode());
            try {
                CredentialPolicy.validateNewPassword(password, confirm, username);
            } catch (IllegalArgumentException e) {
                v.error(PASSWORD, e.getMessage());
            }
            if (username != null && !v.has(USERNAME) && userDao.findByUsername(username).isPresent()) {
                v.error(USERNAME, "اسم المستخدم \"" + username + "\" مستخدم بالفعل.");
            }
            v.throwIfAny();

            User u = new User();
            u.setUsername(username);
            u.setFullName(fullName);
            u.setPhone(phone);
            u.setEmail(email);
            u.setRoleId(role.getRoleId());
            u.setActive(true);
            u.setMustChangePassword(n.mustChangePassword());
            u.setPasswordHash(PasswordHasher.hash(password));
            try {
                TransactionManager.inTransaction(con -> {
                    userDao.insert(con, u);
                    auditLogDao.log(con, adminId, AuditLogDao.USER_CREATED, TABLE, String.valueOf(u.getUserId()),
                            null, "{\"username\":\"" + username + "\",\"role\":\"" + role.getRoleCode() + "\"}",
                            "إنشاء المستخدم " + username + " (" + fullName + ") بدور " + role.getRoleName()
                                    + (n.mustChangePassword() ? "، مع إلزامه بتغيير كلمة المرور عند أول دخول" : ""));
                    return null;
                });
            } catch (DataAccessException e) {
                if (e.violates("UQ_Users_username")) {   // a concurrent creation took the name first
                    throw new ValidationException(USERNAME, "اسم المستخدم \"" + username + "\" مستخدم بالفعل.");
                }
                throw e;
            }
            return UserAccount.of(userDao.findById(u.getUserId()).orElseThrow());
        } finally {
            PasswordHasher.wipe(password);
            PasswordHasher.wipe(confirm);
        }
    }

    private static void profileRules(Validation v, String fullName, String phone, String email) {
        v.required(FULL_NAME, fullName, "أدخل الاسم الكامل.");
        v.maxLength(FULL_NAME, fullName, 100, "الاسم");
        if (phone != null) {
            PartyRules.phone(v, PHONE, phone);
        }
        PartyRules.email(v, EMAIL, email);
    }

    private Role role(Validation v, String roleCode) {
        if (roleCode == null || !ASSIGNABLE_ROLES.contains(roleCode)) {
            v.error(ROLE, "اختر دورًا من الأدوار المتاحة.");
            return null;
        }
        return roleDao.findByCode(roleCode).orElseGet(() -> {
            v.error(ROLE, "الدور غير موجود في قاعدة البيانات.");
            return null;
        });
    }

    // ======================= Editing =======================

    @Override
    public UserAccount update(Changes c) {
        security.requirePermission(Permission.USERS_EDIT);
        int adminId = security.currentUser().getUserId();
        Validation v = new Validation();
        String fullName = trimToNull(c.fullName());
        String phone = PhoneNumbers.normalize(c.phone());
        String email = trimToNull(c.email());
        profileRules(v, fullName, phone, email);
        Role role = role(v, c.roleCode());
        v.throwIfAny();

        TransactionManager.inTransaction(con -> {
            List<Integer> admins = userDao.lockActiveAdmins(con);   // always first: one lock order
            User old = userDao.lockById(con, c.userId())
                    .orElseThrow(() -> new ValidationException(USERNAME, "المستخدم غير موجود."));
            boolean roleChanges = !role.getRoleCode().equals(old.getRoleCode());
            boolean activeChanges = c.active() != old.isActive();
            checkSafety(admins, old, roleChanges ? role.getRoleCode() : old.getRoleCode(),
                    activeChanges ? c.active() : old.isActive(), adminId);

            if (!Objects.equals(fullName, old.getFullName()) || !Objects.equals(phone, old.getPhone())
                    || !Objects.equals(email, old.getEmail())) {
                userDao.updateProfile(con, old.getUserId(), fullName, phone, email);
                auditLogDao.log(con, adminId, AuditLogDao.USER_UPDATED, TABLE, String.valueOf(old.getUserId()),
                        profileJson(old.getFullName(), old.getPhone(), old.getEmail()), profileJson(fullName, phone, email),
                        "تعديل بيانات المستخدم " + old.getUsername());
            }
            if (roleChanges) {
                userDao.updateRole(con, old.getUserId(), role.getRoleId());
                auditLogDao.log(con, adminId, AuditLogDao.USER_ROLE_CHANGED, TABLE, String.valueOf(old.getUserId()),
                        "{\"role\":\"" + old.getRoleCode() + "\"}", "{\"role\":\"" + role.getRoleCode() + "\"}",
                        "تغيير دور المستخدم " + old.getUsername() + " من " + old.getRoleName() + " إلى "
                                + role.getRoleName() + " (يسري من الدخول التالي)");
            }
            if (activeChanges) {
                writeActive(con, old, c.active(), adminId);
            }
            return null;
        });
        return UserAccount.of(userDao.findById(c.userId()).orElseThrow());
    }

    @Override
    public UserAccount setActive(int userId, boolean active) {
        security.requirePermission(Permission.USERS_EDIT);
        int adminId = security.currentUser().getUserId();
        TransactionManager.inTransaction(con -> {
            List<Integer> admins = userDao.lockActiveAdmins(con);
            User old = userDao.lockById(con, userId)
                    .orElseThrow(() -> new ValidationException(USERNAME, "المستخدم غير موجود."));
            if (old.isActive() == active) {
                return null;
            }
            checkSafety(admins, old, old.getRoleCode(), active, adminId);
            writeActive(con, old, active, adminId);
            return null;
        });
        return UserAccount.of(userDao.findById(userId).orElseThrow());
    }

    private void writeActive(Connection con, User old, boolean active, int adminId) {
        userDao.setActive(con, old.getUserId(), active);
        auditLogDao.log(con, adminId, active ? AuditLogDao.USER_ENABLED : AuditLogDao.USER_DISABLED, TABLE,
                String.valueOf(old.getUserId()), (active ? "تفعيل" : "تعطيل") + " المستخدم " + old.getUsername()
                        + (active ? "" : " (لا يستطيع الدخول؛ سجلاته ومستنداته تبقى كما هي)"));
    }

    /**
     * The admin safety rules, checked with the active admins locked (package-private for unit tests).
     *
     * @param activeAdmins the ids of the active administrators (locked)
     * @param target       the user being changed, as stored
     * @param newRole      its role after the change
     * @param newActive    its status after the change
     * @param actingUserId the administrator making the change
     */
    static void checkSafety(List<Integer> activeAdmins, User target, String newRole, boolean newActive, int actingUserId) {
        boolean self = target.getUserId() == actingUserId;
        if (self && !newActive) {
            throw new ValidationException(ACTIVE, "لا يمكنك تعطيل حسابك أثناء استخدامه.");
        }
        if (self && !newRole.equals(target.getRoleCode())) {
            throw new ValidationException(ROLE, "لا يمكنك تغيير دور حسابك بنفسك؛ يغيّره مدير آخر.");
        }
        boolean isActiveAdmin = activeAdmins.contains(target.getUserId());
        boolean staysActiveAdmin = newActive && Role.ADMIN.equals(newRole);
        if (isActiveAdmin && !staysActiveAdmin && activeAdmins.size() <= 1) {
            throw new ValidationException(newActive ? ROLE : ACTIVE,
                    "لا يمكن تنفيذ ذلك: هذا آخر مدير نظام فعال، ويجب أن يبقى مدير نظام فعال واحد على الأقل.");
        }
    }

    private static String profileJson(String name, String phone, String email) {
        return "{\"fullName\":" + json(name) + ",\"phone\":" + json(phone) + ",\"email\":" + json(email) + "}";
    }

    private static String json(String text) {
        return text == null ? "null" : "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ======================= Passwords & locks =======================

    @Override
    public UserAccount resetPassword(int userId, char[] password, char[] confirm, boolean mustChange) {
        try {
            security.requirePermission(Permission.USERS_RESET_PASSWORD);
            int adminId = security.currentUser().getUserId();
            if (userId == adminId) {
                throw new ValidationException(PASSWORD, "لتغيير كلمة مرورك استخدم \"تغيير كلمة المرور\" (يتطلب كلمة المرور الحالية).");
            }
            User target = userDao.findById(userId)
                    .orElseThrow(() -> new ValidationException(USERNAME, "المستخدم غير موجود."));
            try {
                CredentialPolicy.validateNewPassword(password, confirm, target.getUsername());
            } catch (IllegalArgumentException e) {
                throw new ValidationException(PASSWORD, e.getMessage());
            }
            String hash = PasswordHasher.hash(password);
            TransactionManager.inTransaction(con -> {
                userDao.lockById(con, userId).orElseThrow();
                userDao.setPassword(con, userId, hash, mustChange);
                auditLogDao.log(con, adminId, AuditLogDao.PASSWORD_RESET, TABLE, String.valueOf(userId),
                        "إعادة تعيين كلمة مرور المستخدم " + target.getUsername()
                                + (mustChange ? " مع إلزامه بتغييرها عند الدخول التالي" : "")
                                + (target.isLocked() ? "، وإلغاء الإيقاف المؤقت" : ""));
                return null;
            });
            return UserAccount.of(userDao.findById(userId).orElseThrow());
        } finally {
            PasswordHasher.wipe(password);
            PasswordHasher.wipe(confirm);
        }
    }

    @Override
    public UserAccount unlock(int userId) {
        security.requirePermission(Permission.USERS_EDIT);
        int adminId = security.currentUser().getUserId();
        User target = userDao.findById(userId)
                .orElseThrow(() -> new ValidationException(USERNAME, "المستخدم غير موجود."));
        if (target.isLocked() || target.getFailedLoginAttempts() > 0) {
            TransactionManager.inTransaction(con -> {
                userDao.unlock(con, userId);
                auditLogDao.log(con, adminId, AuditLogDao.ACCOUNT_UNLOCKED, TABLE, String.valueOf(userId),
                        "إلغاء الإيقاف المؤقت للمستخدم " + target.getUsername());
                return null;
            });
        }
        return UserAccount.of(userDao.findById(userId).orElseThrow());
    }

    // ======================= Permission matrix =======================

    @Override
    public List<PermissionRow> permissionMatrix() {
        security.requirePermission(Permission.USERS_VIEW);
        return matrix();
    }

    /** Package-private for unit tests: straight from {@link RolePermissions}, the single source of truth. */
    static List<PermissionRow> matrix() {
        List<PermissionRow> rows = new ArrayList<>();
        for (Permission p : Permission.values()) {
            Set<String> roles = new LinkedHashSet<>();
            for (String role : ASSIGNABLE_ROLES) {
                if (RolePermissions.forRole(role).contains(p)) {
                    roles.add(role);
                }
            }
            rows.add(new PermissionRow(p, group(p), roles));
        }
        return rows;
    }

    /** The group of a permission in the matrix. */
    static String group(Permission p) {
        String n = p.name();
        if (n.startsWith("REPORTS_") || n.equals("FINANCIAL_REPORTS")) {
            return "التقارير";
        }
        if (n.startsWith("SALES_") || n.equals("SALE_RETURNS")) {
            return "المبيعات والمرتجعات";
        }
        if (n.startsWith("QUOTATIONS_")) {
            return "عروض الأسعار";
        }
        if (n.startsWith("CUSTOMER")) {
            return "العملاء";
        }
        if (n.startsWith("PRODUCT") || n.startsWith("INVENTORY")) {
            return "المنتجات والمخزون";
        }
        if (n.startsWith("PURCHASE") || n.startsWith("SUPPLIER")) {
            return "المشتريات والموردون";
        }
        if (n.startsWith("CASH") || n.equals("EXPENSES")) {
            return "المالية والخزنة";
        }
        if (n.startsWith("BACKUP_")) {
            return "النسخ الاحتياطي";
        }
        if (n.startsWith("USERS_") || n.startsWith("SETTINGS_") || n.equals("AUDIT_LOG")) {
            return "الإدارة";
        }
        return "عام";
    }
}
