package com.almahwar.service;

import com.almahwar.dao.AuditLogDao;
import com.almahwar.dao.CashTransactionDao;
import com.almahwar.dao.DataAccessException;
import com.almahwar.dao.ProductDao;
import com.almahwar.dao.PurchaseDao;
import com.almahwar.dao.SupplierDao;
import com.almahwar.dao.TransactionManager;
import com.almahwar.dao.UnitDao;
import com.almahwar.model.LedgerEntryType;
import com.almahwar.model.MovementType;
import com.almahwar.model.PartyType;
import com.almahwar.model.PaymentType;
import com.almahwar.model.Permission;
import com.almahwar.model.Product;
import com.almahwar.model.Purchase;
import com.almahwar.model.PurchaseFilter;
import com.almahwar.model.PurchaseItem;
import com.almahwar.model.PurchaseStatus;
import com.almahwar.model.StockMovement;
import com.almahwar.model.Supplier;
import com.almahwar.model.Unit;
import com.almahwar.service.PaymentRules.PaymentPlan;
import com.almahwar.util.MoneyUtil;
import com.almahwar.util.QuantityUtil;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;

import static com.almahwar.service.Validation.trimToNull;

/** Purchases on SQL Server; see {@link PurchaseService} for the posting transaction. */
public class PurchaseServiceImpl implements PurchaseService {

    static final String TABLE = "Purchases";
    static final String REFERENCE = "PURCHASE";

    private final PurchaseDao purchaseDao;
    private final SupplierDao supplierDao;
    private final ProductDao productDao;
    private final UnitDao unitDao;
    private final StockLedger stockLedger;
    private final AccountLedger accountLedger;
    private final CashTransactionDao cashDao;
    private final AuditLogDao auditLogDao;
    private final CostingPolicy costing;
    private final SecurityContext security;

    public PurchaseServiceImpl(PurchaseDao purchaseDao, SupplierDao supplierDao, ProductDao productDao, UnitDao unitDao,
                               StockLedger stockLedger, AccountLedger accountLedger, CashTransactionDao cashDao,
                               AuditLogDao auditLogDao, CostingPolicy costing, SecurityContext security) {
        this.purchaseDao = purchaseDao;
        this.supplierDao = supplierDao;
        this.productDao = productDao;
        this.unitDao = unitDao;
        this.stockLedger = stockLedger;
        this.accountLedger = accountLedger;
        this.cashDao = cashDao;
        this.auditLogDao = auditLogDao;
        this.costing = costing;
        this.security = security;
    }

    // ======================= Reading =======================

    @Override
    public List<Purchase> search(PurchaseFilter filter) {
        security.requirePermission(Permission.PURCHASES_VIEW);
        List<Purchase> rows = purchaseDao.search(filter == null ? PurchaseFilter.all() : filter, MAX_LIST_ROWS);
        rows.forEach(this::hideCostIfNeeded);
        return rows;
    }

    @Override
    public Optional<Purchase> findById(int purchaseId) {
        security.requirePermission(Permission.PURCHASES_VIEW);
        return purchaseDao.findById(purchaseId).map(this::hideCostIfNeeded);
    }

    @Override
    public String suggestNumber() {
        security.requirePermission(Permission.PURCHASES_VIEW);
        return purchaseDao.nextNumber(null);
    }

    private Purchase hideCostIfNeeded(Purchase p) {
        if (!security.hasPermission(Permission.PURCHASE_COST_VIEW)) {
            p.setSubtotal(null);
            p.setDiscountAmount(null);
            p.setTotalAmount(null);
            p.setPaidAmount(null);
            p.getItems().forEach(i -> {
                i.setUnitCost(null);
                i.setDiscountAmount(null);
            });
        }
        return p;
    }

    // ======================= Writing =======================

    @Override
    public Purchase saveDraft(Purchase purchase, PaymentType paymentType) {
        security.requirePermission(Permission.PURCHASES_CREATE);
        int userId = security.currentUser().getUserId();
        if (purchase.getPurchaseId() == null) {
            Optional<Purchase> already = alreadySaved(purchase);
            if (already.isPresent()) {
                return already.get();
            }
        }
        prepare(purchase, paymentType);

        int id = guard(purchase, () -> TransactionManager.inTransaction(con -> {
            if (purchase.getPurchaseId() == null) {
                insertDraft(con, purchase, userId);
            } else {
                if (!purchaseDao.updateDraft(con, purchase)) {
                    throw notDraft(purchase.getPurchaseId());
                }
                purchaseDao.replaceItems(con, purchase.getPurchaseId(), purchase.getItems());
            }
            return purchase.getPurchaseId();
        }));
        return findById(id).orElseThrow();
    }

    @Override
    public Purchase post(int purchaseId) {
        security.requirePermission(Permission.PURCHASES_POST);
        int userId = security.currentUser().getUserId();
        Purchase stored = purchaseDao.findById(purchaseId)
                .orElseThrow(() -> new ValidationException("purchaseId", "فاتورة المشتريات غير موجودة."));
        if (!stored.isDraft()) {
            throw notDraft(purchaseId);
        }
        // Re-check everything: the supplier or a product may have been deactivated since the draft was saved
        Validation v = validateFields(stored);
        v.throwIfAny();
        validateReferences(stored, v);
        v.throwIfAny();

        TransactionManager.inTransaction(con -> {
            applyPosting(con, purchaseId, userId);
            return null;
        });
        return findById(purchaseId).orElseThrow();
    }

    @Override
    public Purchase saveAndPost(Purchase purchase, PaymentType paymentType) {
        security.requirePermission(Permission.PURCHASES_CREATE);
        security.requirePermission(Permission.PURCHASES_POST);
        int userId = security.currentUser().getUserId();
        if (purchase.getPurchaseId() != null) {
            // an existing draft: save the latest changes, then post (two steps, each atomic)
            saveDraft(purchase, paymentType);
            return post(purchase.getPurchaseId());
        }
        Optional<Purchase> already = alreadySaved(purchase);
        if (already.isPresent()) {
            return already.get();
        }
        prepare(purchase, paymentType);

        int id = guard(purchase, () -> TransactionManager.inTransaction(con -> {
            insertDraft(con, purchase, userId);
            applyPosting(con, purchase.getPurchaseId(), userId);
            return purchase.getPurchaseId();
        }));
        return findById(id).orElseThrow();
    }

    @Override
    public void cancelDraft(int purchaseId) {
        security.requirePermission(Permission.PURCHASES_CREATE);
        int userId = security.currentUser().getUserId();
        Purchase stored = purchaseDao.findById(purchaseId)
                .orElseThrow(() -> new ValidationException("purchaseId", "فاتورة المشتريات غير موجودة."));
        TransactionManager.inTransaction(con -> {
            if (!purchaseDao.cancelDraft(con, purchaseId)) {
                throw new ValidationException("purchaseId", stored.getStatus() == PurchaseStatus.POSTED
                        ? "لا يمكن إلغاء فاتورة معتمدة؛ يتم تصحيحها لاحقًا عن طريق مرتجع المشتريات."
                        : "الفاتورة ملغاة مسبقًا.");
            }
            auditLogDao.log(con, userId, AuditLogDao.CANCEL_PURCHASE, TABLE, String.valueOf(purchaseId),
                    "إلغاء مسودة مشتريات " + stored.getPurchaseNo());
            return null;
        });
    }

    // ======================= Posting (inside the caller's transaction) =======================

    /**
     * Applies a DRAFT purchase to stock, product cost, supplier ledger and cash, then marks it POSTED.
     * Runs on the caller's connection: any failure rolls everything back.
     */
    private void applyPosting(Connection con, int purchaseId, int userId) {
        PurchaseStatus status = purchaseDao.lockStatus(con, purchaseId)
                .orElseThrow(() -> new ValidationException("purchaseId", "فاتورة المشتريات غير موجودة."));
        if (status != PurchaseStatus.DRAFT || !purchaseDao.markPosted(con, purchaseId, userId)) {
            throw notDraft(purchaseId);
        }
        Purchase p = purchaseDao.findById(con, purchaseId).orElseThrow();
        String label = "فاتورة مشتريات " + p.getPurchaseNo();

        // 1) stock in + current cost. All products are locked first in product_id order (the same order as sales,
        //    never the order of the lines on screen), then the lines are applied in that order: no deadlock with a
        //    sale or another purchase touching the same products.
        productDao.lockForStockChange(con, p.getItems().stream().map(PurchaseItem::getProductId).toList());
        List<PurchaseItem> lines = p.getItems().stream()
                .sorted(java.util.Comparator.comparing(PurchaseItem::getProductId)).toList();
        for (PurchaseItem item : lines) {
            BigDecimal netCost = item.getNetUnitCost();
            BigDecimal oldCost = productDao.currentPurchasePrice(con, item.getProductId());
            StockMovement m = stockLedger.post(con, item.getProductId(), MovementType.PURCHASE, item.getQuantity(),
                    netCost, REFERENCE, purchaseId, label, userId);
            BigDecimal newCost = MoneyUtil.of(costing.costAfterReceipt(oldCost, m.getQuantityBefore(),
                    item.getQuantity(), netCost));
            productDao.updatePurchasePrice(con, item.getProductId(), newCost);
        }

        // 2) supplier account: we owe the total; what is paid now reduces it at once
        if (p.getTotalAmount().signum() > 0) {
            accountLedger.post(con, PartyType.SUPPLIER, p.getSupplierId(), LedgerEntryType.PURCHASE, p.getTotalAmount(),
                    REFERENCE, purchaseId, p.getPurchaseNo(), withSupplierInvoice(label, p), userId);
        }
        if (p.getPaidAmount().signum() > 0) {
            String paidLabel = "سداد فاتورة مشتريات " + p.getPurchaseNo();
            accountLedger.post(con, PartyType.SUPPLIER, p.getSupplierId(), LedgerEntryType.PAYMENT,
                    p.getPaidAmount().negate(), REFERENCE, purchaseId, p.getPurchaseNo(), paidLabel, userId);
            // 3) the money leaves the cash box / bank
            cashDao.insert(con, CashTransactionDao.OUT, p.getPaidAmount(), p.getPaymentMethod(), REFERENCE, purchaseId,
                    paidLabel, userId);
        }

        // 4) audit
        auditLogDao.log(con, userId, AuditLogDao.POST_PURCHASE, TABLE, String.valueOf(purchaseId),
                "اعتماد " + label + " للمورد " + p.getSupplierName() + " بإجمالي "
                        + MoneyUtil.format(p.getTotalAmount()) + " ومدفوع " + MoneyUtil.format(p.getPaidAmount()));
    }

    private static String withSupplierInvoice(String label, Purchase p) {
        return p.getSupplierInvoiceNo() == null ? label : label + " (فاتورة المورد " + p.getSupplierInvoiceNo() + ")";
    }

    private void insertDraft(Connection con, Purchase p, int userId) {
        p.setPurchaseNo(purchaseDao.nextNumber(con));
        p.setUserId(userId);
        purchaseDao.insert(con, p);
        purchaseDao.replaceItems(con, p.getPurchaseId(), p.getItems());
        auditLogDao.log(con, userId, AuditLogDao.CREATE_PURCHASE, TABLE, String.valueOf(p.getPurchaseId()),
                "إنشاء فاتورة مشتريات " + p.getPurchaseNo() + " بإجمالي " + MoneyUtil.format(p.getTotalAmount()));
    }

    // ======================= Validation =======================

    /** Normalises, computes totals, resolves the payment and validates; throws on any error. */
    private void prepare(Purchase p, PaymentType type) {
        p.setSupplierInvoiceNo(trimToNull(p.getSupplierInvoiceNo()));
        p.setNotes(trimToNull(p.getNotes()));
        if (p.getDiscountAmount() == null) {
            p.setDiscountAmount(MoneyUtil.ZERO);
        }
        for (PurchaseItem i : p.getItems()) {
            if (i.getDiscountAmount() == null) {
                i.setDiscountAmount(MoneyUtil.ZERO);
            }
        }
        Validation v = validateFields(p);
        PaymentRules.check(v, type, p.getPaymentMethod(), p.getPaidAmount(), p.getTotalAmount());
        v.throwIfAny();
        validateReferences(p, v);
        v.throwIfAny();

        PaymentPlan plan = PaymentRules.plan(type, p.getPaymentMethod(), p.getPaidAmount(), p.getTotalAmount());
        p.setPaymentMethod(plan.method());
        p.setPaidAmount(plan.paid());
        // store exact 3-decimal values
        p.setDiscountAmount(MoneyUtil.of(p.getDiscountAmount()));
        for (PurchaseItem i : p.getItems()) {
            i.setQuantity(QuantityUtil.of(i.getQuantity()));
            i.setUnitCost(MoneyUtil.of(i.getUnitCost()));
            i.setDiscountAmount(MoneyUtil.of(i.getDiscountAmount()));
        }
        p.recalculate();
    }

    /** Checks that need no database (also recalculates the totals); package-private for unit tests. */
    static Validation validateFields(Purchase p) {
        Validation v = new Validation();
        if (p.getSupplierId() == null) {
            v.error(SUPPLIER, "اختر المورد.");
        }
        v.maxLength(SUPPLIER_INVOICE_NO, p.getSupplierInvoiceNo(), 50, "رقم فاتورة المورد");
        v.maxLength(NOTES, p.getNotes(), 500, "الملاحظات");
        if (p.getItems().isEmpty()) {
            v.error(ITEMS, "أضف صنفًا واحدًا على الأقل إلى الفاتورة.");
            return v;
        }
        StringBuilder lines = new StringBuilder();
        int n = 0;
        for (PurchaseItem i : p.getItems()) {
            n++;
            String error = itemError(i);
            if (error != null) {
                lines.append(lines.isEmpty() ? "" : "\n").append("السطر ").append(n).append(" (")
                        .append(i.getProductName() == null ? "صنف" : i.getProductName()).append("): ").append(error);
            }
        }
        if (!lines.isEmpty()) {
            v.error(ITEMS, lines.toString());
            return v;
        }
        p.recalculate();
        BigDecimal discount = p.getDiscountAmount();
        if (discount.signum() < 0) {
            v.error(DISCOUNT, "الخصم لا يمكن أن يكون سالبًا.");
        } else if (discount.stripTrailingZeros().scale() > MoneyUtil.SCALE) {
            v.error(DISCOUNT, "الخصم: الحد الأقصى 3 منازل عشرية.");
        } else if (discount.compareTo(p.getSubtotal()) > 0) {
            v.error(DISCOUNT, "الخصم أكبر من مجموع الأصناف؛ لا يمكن أن يكون إجمالي الفاتورة سالبًا.");
        }
        return v;
    }

    /** @return an Arabic error for one line, or {@code null} */
    static String itemError(PurchaseItem i) {
        if (i.getProductId() == null) {
            return "اختر الصنف.";
        }
        BigDecimal qty = i.getQuantity();
        if (qty == null || qty.signum() <= 0) {
            return "الكمية يجب أن تكون أكبر من صفر.";
        }
        if (qty.stripTrailingZeros().scale() > QuantityUtil.SCALE) {
            return "الكمية: الحد الأقصى 3 منازل عشرية.";
        }
        BigDecimal cost = i.getUnitCost();
        if (cost == null || cost.signum() < 0) {
            return "سعر الشراء لا يمكن أن يكون سالبًا.";
        }
        if (cost.stripTrailingZeros().scale() > MoneyUtil.SCALE) {
            return "سعر الشراء: الحد الأقصى 3 منازل عشرية.";
        }
        BigDecimal discount = i.getDiscountAmount() == null ? BigDecimal.ZERO : i.getDiscountAmount();
        if (discount.signum() < 0) {
            return "خصم السطر لا يمكن أن يكون سالبًا.";
        }
        if (discount.compareTo(qty.multiply(cost)) > 0) {
            return "خصم السطر أكبر من قيمته.";
        }
        return null;
    }

    /** Supplier, products, units and duplicates (database). */
    private void validateReferences(Purchase p, Validation v) {
        Optional<Supplier> supplier = supplierDao.findById(p.getSupplierId());
        if (supplier.isEmpty()) {
            v.error(SUPPLIER, "المورد غير موجود.");
        } else if (!supplier.get().isActive()) {
            v.error(SUPPLIER, "المورد \"" + supplier.get().getName() + "\" غير نشط؛ أعد تفعيله أولًا.");
        }
        if (p.getSupplierInvoiceNo() != null && supplier.isPresent()
                && purchaseDao.existsSupplierInvoice(p.getSupplierId(), p.getSupplierInvoiceNo(), p.getPurchaseId())) {
            v.error(SUPPLIER_INVOICE_NO, "فاتورة المورد رقم \"" + p.getSupplierInvoiceNo()
                    + "\" مسجلة مسبقًا لهذا المورد.");
        }
        StringBuilder lines = new StringBuilder();
        int n = 0;
        for (PurchaseItem i : p.getItems()) {
            n++;
            Optional<Product> product = productDao.findById(i.getProductId());
            String error = null;
            if (product.isEmpty()) {
                error = "الصنف غير موجود.";
            } else if (!product.get().isActive()) {
                error = "الصنف \"" + product.get().getNameAr() + "\" معطّل.";
            } else {
                Unit unit = unitDao.findById(product.get().getUnitId()).orElseThrow();
                if (!unit.isAllowsDecimal() && i.getQuantity().stripTrailingZeros().scale() > 0) {
                    error = "الوحدة \"" + unit.getNameAr() + "\" لا تقبل الكسور.";
                }
            }
            if (error != null) {
                lines.append(lines.isEmpty() ? "" : "\n").append("السطر ").append(n).append(": ").append(error);
            }
        }
        if (!lines.isEmpty()) {
            v.error(ITEMS, lines.toString());
        }
    }

    // ======================= Duplicate protection =======================

    /** A retried request (same request id) returns what the first one saved instead of saving again. */
    private Optional<Purchase> alreadySaved(Purchase p) {
        if (p.getRequestId() == null) {
            return Optional.empty();
        }
        return purchaseDao.findIdByRequest(p.getRequestId()).flatMap(this::findById);
    }

    private int guard(Purchase p, java.util.function.Supplier<Integer> save) {
        boolean isNew = p.getPurchaseId() == null;
        try {
            return save.get();
        } catch (RuntimeException e) {
            if (isNew) {
                // the transaction rolled back: forget the id/number it had assigned, so a retry starts clean
                p.setPurchaseId(null);
                p.setPurchaseNo(null);
                p.getItems().forEach(i -> {
                    i.setPurchaseItemId(null);
                    i.setPurchaseId(null);
                });
            }
            throw translate(p, e);
        }
    }

    private RuntimeException translate(Purchase p, RuntimeException ex) {
        if (ex instanceof DataAccessException e) {
            if (e.violates("UX_Purchases_request") && p.getRequestId() != null) {
                return new ValidationException("requestId",
                        "تم حفظ هذه الفاتورة مسبقًا (ضغطة مكررة)؛ افتح قائمة المشتريات لعرضها.");
            }
            if (e.violates("UX_Purchases_supplier_invoice")) {
                return new ValidationException(SUPPLIER_INVOICE_NO, "رقم فاتورة المورد مسجل مسبقًا لهذا المورد.");
            }
            if (e.violates("UQ_Purchases_purchase_no")) {
                return new ValidationException("purchaseNo", "رقم الفاتورة مستخدم؛ أعد المحاولة.");
            }
        }
        return ex;
    }

    private static ValidationException notDraft(int purchaseId) {
        return new ValidationException("purchaseId",
                "الفاتورة معتمدة أو ملغاة مسبقًا؛ لا يمكن تعديلها أو اعتمادها مرة أخرى.");
    }
}
