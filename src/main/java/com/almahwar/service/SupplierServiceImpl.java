package com.almahwar.service;

import com.almahwar.dao.AuditLogDao;
import com.almahwar.dao.DataAccessException;
import com.almahwar.dao.SupplierDao;
import com.almahwar.dao.TransactionManager;
import com.almahwar.model.AccountStatement;
import com.almahwar.model.LedgerEntryType;
import com.almahwar.model.PartyFilter;
import com.almahwar.model.PartyType;
import com.almahwar.model.Permission;
import com.almahwar.model.Supplier;
import com.almahwar.util.MoneyUtil;
import com.almahwar.util.PhoneNumbers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static com.almahwar.service.Validation.trimToNull;

/** Suppliers on SQL Server through the DAOs; balances only through {@link AccountLedger}. */
public class SupplierServiceImpl implements SupplierService {

    static final String TABLE = "Suppliers";

    private final SupplierDao supplierDao;
    private final AccountLedger ledger;
    private final AuditLogDao auditLogDao;
    private final SecurityContext security;

    public SupplierServiceImpl(SupplierDao supplierDao, AccountLedger ledger, AuditLogDao auditLogDao,
                               SecurityContext security) {
        this.supplierDao = supplierDao;
        this.ledger = ledger;
        this.auditLogDao = auditLogDao;
        this.security = security;
    }

    @Override
    public List<Supplier> search(PartyFilter filter) {
        security.requirePermission(Permission.SUPPLIERS_VIEW);
        PartyFilter f = filter == null ? PartyFilter.all() : filter;
        if (!canSeeBalances() && f.withBalance()) {
            f = new PartyFilter(f.text(), f.status(), false, false);
        }
        return hideBalances(supplierDao.search(f));
    }

    @Override
    public Optional<Supplier> findById(int supplierId) {
        security.requirePermission(Permission.SUPPLIERS_VIEW);
        return supplierDao.findById(supplierId).map(s -> hideBalances(List.of(s)).get(0));
    }

    @Override
    public String suggestCode() {
        security.requirePermission(Permission.SUPPLIERS_VIEW);
        return supplierDao.nextCode(null);
    }

    @Override
    public List<Supplier> findSamePhone(String phone, Integer excludeSupplierId) {
        security.requirePermission(Permission.SUPPLIERS_VIEW);
        String normalized = PhoneNumbers.normalize(phone);
        if (normalized == null || PhoneNumbers.validate(normalized) != null) {
            return List.of();
        }
        return hideBalances(supplierDao.findByPhone(normalized, excludeSupplierId));
    }

    private boolean canSeeBalances() {
        return security.hasPermission(Permission.SUPPLIER_BALANCE_VIEW);
    }

    private List<Supplier> hideBalances(List<Supplier> suppliers) {
        if (!canSeeBalances()) {
            suppliers.forEach(s -> {
                s.setBalance(null);
                s.setOpeningBalance(null);
            });
        }
        return suppliers;
    }

    @Override
    public Supplier create(Supplier supplier, BigDecimal openingBalance) {
        security.requirePermission(Permission.SUPPLIERS_EDIT);
        int userId = security.currentUser().getUserId();
        BigDecimal opening = openingBalance == null ? MoneyUtil.ZERO : openingBalance;
        normalize(supplier);
        if (!canSeeBalances() && opening.signum() != 0) {
            throw new AccessDeniedException("ليس لديك صلاحية إدخال رصيد افتتاحي للموردين.");
        }

        Validation v = validate(supplier);
        v.amount(OPENING_BALANCE, opening, "الرصيد الافتتاحي");
        v.throwIfAny();   // input first, then the database check
        if (supplier.getSupplierCode() != null && supplierDao.existsByCode(supplier.getSupplierCode(), null)) {
            v.error(CODE, "كود المورد \"" + supplier.getSupplierCode() + "\" مستخدم لمورد آخر.");
        }
        v.throwIfAny();

        supplier.setOpeningBalance(MoneyUtil.of(opening));
        int id = unique(() -> TransactionManager.inTransaction(con -> {
            if (supplier.getSupplierCode() == null) {
                supplier.setSupplierCode(supplierDao.nextCode(con));
            }
            int supplierId = supplierDao.insert(con, supplier);
            if (opening.signum() != 0) {
                ledger.post(con, PartyType.SUPPLIER, supplierId, LedgerEntryType.OPENING_BALANCE, opening,
                        "SUPPLIER", supplierId, supplier.getSupplierCode(), "رصيد افتتاحي", userId);
            }
            auditLogDao.log(con, userId, AuditLogDao.INSERT, TABLE, String.valueOf(supplierId),
                    "إضافة مورد " + supplier.getSupplierCode() + " - " + supplier.getName()
                            + (opening.signum() != 0 ? " برصيد افتتاحي " + MoneyUtil.format(opening) : ""));
            return supplierId;
        }));
        return findById(id).orElseThrow();
    }

    @Override
    public Supplier update(Supplier supplier) {
        security.requirePermission(Permission.SUPPLIERS_EDIT);
        if (supplier.getSupplierId() == null) {
            throw new IllegalArgumentException("update() needs a saved supplier; use create()");
        }
        int userId = security.currentUser().getUserId();
        supplierDao.findById(supplier.getSupplierId())
                .orElseThrow(() -> new ValidationException("supplierId", "المورد غير موجود."));
        normalize(supplier);

        Validation v = validate(supplier);
        if (supplier.getSupplierCode() == null) {
            v.error(CODE, "كود المورد مطلوب.");
        } else if (supplierDao.existsByCode(supplier.getSupplierCode(), supplier.getSupplierId())) {
            v.error(CODE, "كود المورد \"" + supplier.getSupplierCode() + "\" مستخدم لمورد آخر.");
        }
        v.throwIfAny();

        unique(() -> {
            supplierDao.update(supplier);
            return null;
        });
        auditLogDao.log(userId, AuditLogDao.UPDATE, TABLE, String.valueOf(supplier.getSupplierId()),
                "تعديل مورد " + supplier.getSupplierCode() + " - " + supplier.getName());
        return findById(supplier.getSupplierId()).orElseThrow();
    }

    @Override
    public void setActive(int supplierId, boolean active) {
        security.requirePermission(Permission.SUPPLIERS_EDIT);
        Supplier existing = supplierDao.findById(supplierId)
                .orElseThrow(() -> new ValidationException("supplierId", "المورد غير موجود."));
        supplierDao.setActive(supplierId, active);
        auditLogDao.log(security.currentUser().getUserId(), active ? AuditLogDao.ACTIVATE : AuditLogDao.DEACTIVATE,
                TABLE, String.valueOf(supplierId), (active ? "تفعيل" : "تعطيل") + " مورد " + existing.getSupplierCode());
    }

    @Override
    public Supplier requireActive(int supplierId) {
        security.requirePermission(Permission.SUPPLIERS_VIEW);
        Supplier s = supplierDao.findById(supplierId)
                .orElseThrow(() -> new ValidationException("supplierId", "المورد غير موجود."));
        if (!s.isActive()) {
            throw new ValidationException("supplierId",
                    "المورد \"" + s.getName() + "\" غير نشط؛ أعد تفعيله أولًا لاستخدامه في عملية جديدة.");
        }
        return hideBalances(List.of(s)).get(0);
    }

    @Override
    public AccountStatement statement(int supplierId, LocalDate from, LocalDate to, String search) {
        security.requirePermission(Permission.SUPPLIER_BALANCE_VIEW);
        Supplier s = supplierDao.findById(supplierId)
                .orElseThrow(() -> new ValidationException("supplierId", "المورد غير موجود."));
        return ledger.statement(PartyType.SUPPLIER, supplierId, s.getSupplierCode(), s.getName(), from, to, search);
    }

    @Override
    public List<Integer> balanceMismatches() {
        security.requirePermission(Permission.SUPPLIER_BALANCE_VIEW);
        return ledger.balanceMismatches(PartyType.SUPPLIER);
    }

    private static void normalize(Supplier s) {
        s.setSupplierCode(trimToNull(s.getSupplierCode()));
        s.setName(trimToNull(s.getName()));
        s.setContactPerson(trimToNull(s.getContactPerson()));
        s.setPhone(PhoneNumbers.normalize(s.getPhone()));
        s.setPhone2(PhoneNumbers.normalize(s.getPhone2()));
        s.setEmail(trimToNull(s.getEmail()));
        s.setCountry(trimToNull(s.getCountry()));
        s.setArea(trimToNull(s.getArea()));
        s.setAddress(trimToNull(s.getAddress()));
        s.setNotes(trimToNull(s.getNotes()));
    }

    /** Checks that need no database; package-private for unit tests. */
    static Validation validate(Supplier s) {
        Validation v = new Validation();
        v.maxLength(CODE, s.getSupplierCode(), 30, "كود المورد");
        v.required(NAME, s.getName(), "اسم المورد مطلوب.");
        v.maxLength(NAME, s.getName(), 150, "اسم المورد");
        v.maxLength(CONTACT_PERSON, s.getContactPerson(), 100, "اسم المسؤول");
        PartyRules.phone(v, PHONE, s.getPhone());
        PartyRules.phone(v, PHONE2, s.getPhone2());
        PartyRules.email(v, EMAIL, s.getEmail());
        v.maxLength(COUNTRY, s.getCountry(), 50, "الدولة");
        v.maxLength(AREA, s.getArea(), 100, "المنطقة");
        v.maxLength(ADDRESS, s.getAddress(), 250, "العنوان");
        v.maxLength(NOTES, s.getNotes(), 500, "الملاحظات");
        return v;
    }

    private static <T> T unique(java.util.function.Supplier<T> save) {
        try {
            return save.get();
        } catch (DataAccessException e) {
            if (e.violates("UQ_Suppliers_code")) {
                throw new ValidationException(CODE, "كود المورد مستخدم لمورد آخر.");
            }
            throw e;
        }
    }
}
