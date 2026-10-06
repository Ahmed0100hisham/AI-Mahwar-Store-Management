package com.almahwar.service;

import com.almahwar.dao.AuditLogDao;
import com.almahwar.dao.CashTransactionDao;
import com.almahwar.dao.CustomerDao;
import com.almahwar.dao.DataAccessException;
import com.almahwar.dao.ProductDao;
import com.almahwar.dao.ProductDao.LockedStock;
import com.almahwar.dao.PurchaseDao;
import com.almahwar.dao.ReturnDao;
import com.almahwar.dao.SaleDao;
import com.almahwar.dao.SupplierDao;
import com.almahwar.dao.TransactionManager;
import com.almahwar.model.CashMovement;
import com.almahwar.model.CashSource;
import com.almahwar.model.Customer;
import com.almahwar.model.LedgerEntry;
import com.almahwar.model.LedgerEntryType;
import com.almahwar.model.MovementType;
import com.almahwar.model.PartyType;
import com.almahwar.model.PaymentMethod;
import com.almahwar.model.Permission;
import com.almahwar.model.Purchase;
import com.almahwar.model.PurchaseItem;
import com.almahwar.model.PurchaseStatus;
import com.almahwar.model.RefundPlan;
import com.almahwar.model.ReturnDocument;
import com.almahwar.model.ReturnFilter;
import com.almahwar.model.ReturnKind;
import com.almahwar.model.ReturnLine;
import com.almahwar.model.Sale;
import com.almahwar.model.SaleItem;
import com.almahwar.model.SaleStatus;
import com.almahwar.model.Supplier;
import com.almahwar.util.MoneyUtil;
import com.almahwar.util.QuantityUtil;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.almahwar.service.Validation.trimToNull;

/** Returns on SQL Server; see {@link ReturnService} for the transactions. */
public class ReturnServiceImpl implements ReturnService {

    private final ReturnDao returnDao;
    private final SaleDao saleDao;
    private final PurchaseDao purchaseDao;
    private final CustomerDao customerDao;
    private final SupplierDao supplierDao;
    private final ProductDao productDao;
    private final StockLedger stockLedger;
    private final AccountLedger accountLedger;
    private final CashTransactionDao cashDao;
    private final AuditLogDao auditLogDao;
    private final SecurityContext security;

    public ReturnServiceImpl(ReturnDao returnDao, SaleDao saleDao, PurchaseDao purchaseDao, CustomerDao customerDao,
                             SupplierDao supplierDao, ProductDao productDao, StockLedger stockLedger,
                             AccountLedger accountLedger, CashTransactionDao cashDao, AuditLogDao auditLogDao,
                             SecurityContext security) {
        this.returnDao = returnDao;
        this.saleDao = saleDao;
        this.purchaseDao = purchaseDao;
        this.customerDao = customerDao;
        this.supplierDao = supplierDao;
        this.productDao = productDao;
        this.stockLedger = stockLedger;
        this.accountLedger = accountLedger;
        this.cashDao = cashDao;
        this.auditLogDao = auditLogDao;
        this.security = security;
    }

    private static Permission permission(ReturnKind kind) {
        return kind == ReturnKind.SALE ? Permission.SALE_RETURNS : Permission.PURCHASE_RETURNS;
    }

    // ======================= Pure rules (unit-tested) =======================

    /**
     * Net value of one unit of an original line as actually invoiced: the line total (after its line discount) with
     * its share of the invoice discount, per unit, rounded <b>down</b> to 3 decimals — a return never gives back more
     * than was charged.
     */
    static BigDecimal netUnitValue(BigDecimal lineTotal, BigDecimal quantity, BigDecimal docSubtotal, BigDecimal docTotal) {
        if (quantity == null || quantity.signum() <= 0 || lineTotal == null || docSubtotal == null || docSubtotal.signum() == 0) {
            return MoneyUtil.ZERO;
        }
        BigDecimal share = lineTotal.multiply(docTotal).divide(docSubtotal, 12, RoundingMode.HALF_UP);
        return share.divide(quantity, MoneyUtil.SCALE, RoundingMode.DOWN);
    }

    /**
     * How a return value is settled (conservative):
     * <ul>
     *   <li>walk-in customer ({@code balance == null}): no account, the whole value is paid back;</li>
     *   <li>otherwise the value first reduces what the party owes; only the part beyond that debt is money back,
     *       so no unintended credit balance is created and nothing is refunded that was never paid.</li>
     * </ul>
     */
    static RefundPlan settle(BigDecimal value, BigDecimal balance) {
        BigDecimal v = MoneyUtil.of(value);
        if (balance == null) {
            return new RefundPlan(v, MoneyUtil.ZERO, v, null);
        }
        BigDecimal debt = MoneyUtil.of(balance).max(BigDecimal.ZERO);
        BigDecimal refund = MoneyUtil.of(v.subtract(debt).max(BigDecimal.ZERO));
        return new RefundPlan(v, MoneyUtil.of(v.subtract(refund)), refund, MoneyUtil.of(balance));
    }

    // ======================= Reading =======================

    @Override
    public List<ReturnDocument> search(ReturnKind kind, ReturnFilter filter) {
        security.requirePermission(permission(kind));
        List<ReturnDocument> rows = returnDao.search(kind, filter == null ? ReturnFilter.all() : filter, MAX_LIST_ROWS);
        rows.forEach(this::hideCost);
        return rows;
    }

    @Override
    public Optional<ReturnDocument> findById(ReturnKind kind, int returnId) {
        security.requirePermission(permission(kind));
        return returnDao.findById(kind, returnId).map(this::hideCost);
    }

    @Override
    public String suggestNumber(ReturnKind kind) {
        security.requirePermission(permission(kind));
        return returnDao.nextNumber(null, kind);
    }

    @Override
    public List<ReturnLine> returnableLines(ReturnKind kind, int originalId) {
        security.requirePermission(permission(kind));
        Original o = original(null, kind, originalId);
        List<ReturnLine> lines = o.lines(returnDao.returnedQuantities(null, kind, originalId));
        if (kind == ReturnKind.SALE && !security.hasPermission(Permission.SALES_COST_VIEW)) {
            lines.forEach(l -> l.setUnitCost(null));
        }
        return lines;
    }

    @Override
    public RefundPlan plan(ReturnKind kind, int originalId, BigDecimal value) {
        security.requirePermission(permission(kind));
        Original o = original(null, kind, originalId);
        BigDecimal balance = o.walkIn() ? null : kind == ReturnKind.SALE
                ? customerDao.findById(o.partyId()).map(Customer::getBalance).orElse(BigDecimal.ZERO)
                : supplierDao.findById(o.partyId()).map(Supplier::getBalance).orElse(BigDecimal.ZERO);
        RefundPlan p = settle(value == null ? BigDecimal.ZERO : value, balance);
        boolean seesBalance = security.hasPermission(kind == ReturnKind.SALE
                ? Permission.CUSTOMER_BALANCE_VIEW : Permission.SUPPLIER_BALANCE_VIEW);
        return seesBalance ? p : new RefundPlan(p.value(), p.accountCredit(), p.refund(), null);
    }

    private ReturnDocument hideCost(ReturnDocument d) {
        if (d.getKind() == ReturnKind.SALE && !security.hasPermission(Permission.SALES_COST_VIEW)) {
            d.setCostTotal(null);
            d.getLines().forEach(l -> l.setUnitCost(null));
        }
        return d;
    }

    // ======================= The original document =======================

    /** A posted sale / purchase reduced to what a return needs. */
    record Original(ReturnKind kind, int id, String number, int partyId, boolean walkIn, BigDecimal total,
                            PaymentMethod method, List<ReturnLine> base) {

        /** The original lines with already returned and remaining quantities. */
        List<ReturnLine> lines(Map<Integer, BigDecimal> returned) {
            List<ReturnLine> out = new ArrayList<>();
            for (ReturnLine b : base) {
                ReturnLine l = b.copy();
                l.setReturnedQuantity(QuantityUtil.of(returned.getOrDefault(b.getOriginalItemId(), BigDecimal.ZERO)));
                l.setQuantity(null);
                out.add(l);
            }
            return out;
        }
    }

    private Original original(Connection con, ReturnKind kind, int originalId) {
        if (kind == ReturnKind.SALE) {
            Sale s = (con == null ? saleDao.findById(originalId) : saleDao.findById(con, originalId))
                    .orElseThrow(() -> new ValidationException(ORIGINAL, "فاتورة البيع غير موجودة."));
            if (s.getStatus() != SaleStatus.POSTED) {
                throw new ValidationException(ORIGINAL, "لا يمكن الإرجاع من فاتورة غير معتمدة.");
            }
            List<ReturnLine> base = new ArrayList<>();
            for (SaleItem i : s.getItems()) {
                ReturnLine l = new ReturnLine();
                l.setOriginalItemId(i.getSaleItemId());
                l.setProductId(i.getProductId());
                l.setProductCode(i.getProductCode());
                l.setProductName(i.getProductName());
                l.setUnitName(i.getUnitName());
                l.setUnitAllowsDecimal(i.isUnitAllowsDecimal());
                l.setOriginalQuantity(QuantityUtil.of(i.getQuantity()));
                l.setUnitPrice(netUnitValue(i.getLineTotal(), i.getQuantity(), s.getSubtotal(), s.getTotalAmount()));
                l.setUnitCost(MoneyUtil.of(i.getUnitCost()));
                base.add(l);
            }
            return new Original(kind, originalId, s.getSaleNo(), s.getCustomerId(), s.isWalkIn(), s.getTotalAmount(),
                    s.getPaymentMethod(), base);
        }
        Purchase p = (con == null ? purchaseDao.findById(originalId) : purchaseDao.findById(con, originalId))
                .orElseThrow(() -> new ValidationException(ORIGINAL, "فاتورة المشتريات غير موجودة."));
        if (p.getStatus() != PurchaseStatus.POSTED) {
            throw new ValidationException(ORIGINAL, "لا يمكن الإرجاع من فاتورة غير معتمدة.");
        }
        List<ReturnLine> base = new ArrayList<>();
        for (PurchaseItem i : p.getItems()) {
            ReturnLine l = new ReturnLine();
            l.setOriginalItemId(i.getPurchaseItemId());
            l.setProductId(i.getProductId());
            l.setProductCode(i.getProductCode());
            l.setProductName(i.getProductName());
            l.setUnitName(i.getUnitName());
            l.setUnitAllowsDecimal(i.isUnitAllowsDecimal());
            l.setOriginalQuantity(QuantityUtil.of(i.getQuantity()));
            BigDecimal net = netUnitValue(i.getLineTotal(), i.getQuantity(), p.getSubtotal(), p.getTotalAmount());
            l.setUnitPrice(net);
            l.setUnitCost(net);
            base.add(l);
        }
        return new Original(kind, originalId, p.getPurchaseNo(), p.getSupplierId(), false, p.getTotalAmount(),
                p.getPaymentMethod(), base);
    }

    // ======================= Posting =======================

    @Override
    public ReturnDocument create(ReturnDocument r) {
        if (r.getKind() == null) {
            throw new IllegalArgumentException("Return kind is required");
        }
        ReturnKind kind = r.getKind();
        security.requirePermission(permission(kind));
        int userId = security.currentUser().getUserId();
        r.setNotes(trimToNull(r.getNotes()));
        validateFields(r).throwIfAny();

        Optional<ReturnDocument> already = alreadySaved(r);
        if (already.isPresent()) {
            return already.get();
        }
        r.setUserId(userId);
        try {
            Integer id = TransactionManager.inTransaction(con -> post(con, r, userId));
            if (id == null) {
                return alreadySaved(r).orElseThrow();   // a concurrent retry of this request saved it first
            }
            ReturnDocument saved = findById(kind, id).orElseThrow();
            saved.setBalanceAfter(r.getBalanceAfter());
            return saved;
        } catch (RuntimeException e) {
            r.setReturnId(null);
            r.setReturnNo(null);
            r.getLines().forEach(l -> l.setReturnItemId(null));
            if (e instanceof DataAccessException dae && r.getRequestId() != null
                    && dae.violates(kind == ReturnKind.SALE ? "UX_Sale_Returns_request" : "UX_Purchase_Returns_request")) {
                return alreadySaved(r).orElseThrow(() -> e);
            }
            throw e;
        }
    }

    /** @return the new return's id, or {@code null} when this request was already saved */
    private Integer post(Connection con, ReturnDocument r, int userId) {
        ReturnKind kind = r.getKind();
        boolean sale = kind == ReturnKind.SALE;

        // 1) lock the original document: two returns of the same invoice are checked one after the other
        boolean posted = sale
                ? saleDao.lockStatus(con, r.getOriginalId()).map(s -> s == SaleStatus.POSTED)
                .orElseThrow(() -> new ValidationException(ORIGINAL, "فاتورة البيع غير موجودة."))
                : purchaseDao.lockStatus(con, r.getOriginalId()).map(s -> s == PurchaseStatus.POSTED)
                .orElseThrow(() -> new ValidationException(ORIGINAL, "فاتورة المشتريات غير موجودة."));
        if (!posted) {
            throw new ValidationException(ORIGINAL, "لا يمكن الإرجاع من فاتورة غير معتمدة.");
        }
        if (r.getRequestId() != null && returnDao.findIdByRequest(con, kind, r.getRequestId()).isPresent()) {
            return null;
        }
        Original o = original(con, kind, r.getOriginalId());

        // 2) what is still returnable, with the original's net prices and historical costs
        List<ReturnLine> lines = buildLines(o, r.getLines(), returnDao.returnedQuantities(con, kind, o.id()));
        BigDecimal sum = lines.stream().map(ReturnLine::getLineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal left = MoneyUtil.of(o.total().subtract(returnDao.returnedValue(con, kind, o.id())));
        BigDecimal value = MoneyUtil.of(sum.min(left.max(BigDecimal.ZERO)));
        BigDecimal cost = MoneyUtil.of(lines.stream().map(ReturnLine::getCostTotal).reduce(BigDecimal.ZERO, BigDecimal::add));

        // 3) locks in the same order as the original postings: sale → customer → products; purchase → products → supplier
        String partyName;
        BigDecimal balance;
        Map<Integer, LockedStock> stock;
        List<Integer> productIds = lines.stream().map(ReturnLine::getProductId).toList();
        if (sale) {
            if (o.walkIn()) {
                partyName = null;
                balance = null;
            } else {
                Customer c = customerDao.lockForUpdate(con, o.partyId())
                        .orElseThrow(() -> new ValidationException(ORIGINAL, "العميل غير موجود."));
                partyName = c.getName();
                balance = c.getBalance();
            }
            stock = lockProducts(con, productIds);
        } else {
            stock = lockProducts(con, productIds);
            checkStock(lines, stock);
            Supplier s = supplierDao.lockForUpdate(con, o.partyId())
                    .orElseThrow(() -> new ValidationException(ORIGINAL, "المورد غير موجود."));
            partyName = s.getName();
            balance = s.getBalance();
        }
        RefundPlan plan = settle(value, balance);

        // 4) the return document
        r.setReturnNo(returnDao.nextNumber(con, kind));
        r.setPartyId(o.partyId());
        r.setTotalAmount(plan.value());
        r.setCostTotal(sale ? cost : plan.value());
        r.setRefundAmount(plan.refund());
        if (!plan.movesMoney()) {
            r.setRefundMethod(PaymentMethod.CREDIT);   // only the balance changed
        }
        r.setLines(lines);
        returnDao.insert(con, r);
        for (ReturnLine l : lines) {
            returnDao.insertLine(con, kind, r.getReturnId(), l);
        }
        String label = kind.getLabelAr() + " " + r.getReturnNo() + " من الفاتورة " + o.number();

        // 5) stock: back in for a sale return, out for a purchase return (product_id order, like the locks)
        for (ReturnLine l : lines.stream().sorted(Comparator.comparing(ReturnLine::getProductId)).toList()) {
            stockLedger.post(con, l.getProductId(), sale ? MovementType.SALE_RETURN : MovementType.PURCHASE_RETURN,
                    l.getQuantity(), l.getUnitCost(), kind.name() + "_RETURN", r.getReturnId(), label, userId);
        }

        // 6) the party's account (never for the walk-in customer)
        String reference = sale ? CashSource.SALE_RETURN.code() : CashSource.PURCHASE_RETURN.code();
        if (balance != null) {
            PartyType party = kind.getParty();
            LedgerEntry e = accountLedger.post(con, party, o.partyId(),
                    sale ? LedgerEntryType.SALE_RETURN : LedgerEntryType.PURCHASE_RETURN, plan.value().negate(),
                    reference, r.getReturnId(), r.getReturnNo(), label + " — " + r.getReason().getLabelAr(), userId);
            if (plan.movesMoney()) {
                e = accountLedger.post(con, party, o.partyId(), LedgerEntryType.PAYMENT, plan.refund(), reference,
                        r.getReturnId(), r.getReturnNo(), (sale ? "رد مبلغ للعميل عن " : "استرداد مبلغ من المورد عن ")
                                + label + " (" + r.getRefundMethod().getLabelAr() + ")", userId);
            }
            r.setBalanceAfter(MoneyUtil.of(e.getRunningBalance()));
        }

        // 7) money that really moves: paid back to the customer (OUT) / received from the supplier (IN)
        if (plan.movesMoney()) {
            CashMovement cash = new CashMovement();
            cash.setDirection(sale ? CashMovement.Direction.OUT : CashMovement.Direction.IN);
            cash.setAmount(plan.refund());
            cash.setPaymentMethod(r.getRefundMethod());
            cash.setSource(sale ? CashSource.SALE_RETURN : CashSource.PURCHASE_RETURN);
            cash.setSourceId(r.getReturnId());
            cash.setDescription((sale ? "رد مبلغ " : "استرداد من المورد - ") + label);
            cash.setNotes(r.getNotes());
            cash.setUserId(userId);
            cashDao.insert(con, cash);
        }

        // 8) audit
        auditLogDao.log(con, userId, sale ? AuditLogDao.SALE_RETURN_CREATED : AuditLogDao.PURCHASE_RETURN_CREATED,
                sale ? "Sale_Returns" : "Purchase_Returns", String.valueOf(r.getReturnId()),
                label + " (المستند الأصلي " + o.id() + ")" + (partyName == null ? "" : " — " + partyName)
                        + ": القيمة " + MoneyUtil.format(plan.value()) + "، من الحساب " + MoneyUtil.format(plan.accountCredit())
                        + "، " + (sale ? "مردود نقدًا " : "مستلم نقدًا ") + MoneyUtil.format(plan.refund())
                        + "، السبب: " + r.getReason().getLabelAr());
        return r.getReturnId();
    }

    private Map<Integer, LockedStock> lockProducts(Connection con, List<Integer> productIds) {
        return productDao.lockForStockChange(con, productIds).stream()
                .collect(Collectors.toMap(LockedStock::productId, Function.identity()));
    }

    /** A purchase return takes goods out of stock: they must still be there. */
    private static void checkStock(List<ReturnLine> lines, Map<Integer, LockedStock> stock) {
        Map<Integer, BigDecimal> wanted = new LinkedHashMap<>();
        lines.forEach(l -> wanted.merge(l.getProductId(), l.getQuantity(), BigDecimal::add));
        StringBuilder errors = new StringBuilder();
        for (Map.Entry<Integer, BigDecimal> w : wanted.entrySet()) {
            LockedStock s = stock.get(w.getKey());
            BigDecimal available = s == null ? BigDecimal.ZERO : s.quantity();
            if (w.getValue().compareTo(available) > 0) {
                errors.append(errors.isEmpty() ? "" : "\n").append("\"").append(s == null ? "صنف" : s.nameAr())
                        .append("\": المخزون الحالي ").append(QuantityUtil.format(available))
                        .append("، المطلوب إرجاعه ").append(QuantityUtil.format(w.getValue()));
            }
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(LINES, "لا يمكن إرجاع كمية أكبر من المخزون الحالي:\n" + errors);
        }
    }

    /**
     * The requested quantities checked against the original lines and what was already returned; returns the lines
     * to save with the original's net unit value and historical cost.
     */
    static List<ReturnLine> buildLines(Original o, List<ReturnLine> requested, Map<Integer, BigDecimal> returned) {
        Map<Integer, ReturnLine> originals = new LinkedHashMap<>();
        o.lines(returned).forEach(l -> originals.put(l.getOriginalItemId(), l));
        List<ReturnLine> out = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        StringBuilder errors = new StringBuilder();
        for (ReturnLine req : requested) {
            BigDecimal q = req.getQuantity();
            if (q == null || q.signum() == 0) {
                continue;   // a line left at zero is simply not returned
            }
            ReturnLine orig = originals.get(req.getOriginalItemId());
            String error = null;
            if (orig == null || !seen.add(req.getOriginalItemId())) {
                error = "سطر غير موجود في الفاتورة الأصلية.";
            } else if (q.signum() < 0) {
                error = "كمية المرتجع لا يمكن أن تكون سالبة.";
            } else if (q.stripTrailingZeros().scale() > QuantityUtil.SCALE) {
                error = "الكمية: الحد الأقصى 3 منازل عشرية.";
            } else if (!orig.isUnitAllowsDecimal() && q.stripTrailingZeros().scale() > 0) {
                error = "الوحدة \"" + orig.getUnitName() + "\" لا تقبل الكسور.";
            } else if (q.compareTo(orig.getRemainingQuantity()) > 0) {
                error = "الكمية " + QuantityUtil.format(q) + " أكبر من المتبقي القابل للإرجاع "
                        + QuantityUtil.format(orig.getRemainingQuantity()) + " (المباع/المشترى "
                        + QuantityUtil.format(orig.getOriginalQuantity()) + "، المرتجع سابقًا "
                        + QuantityUtil.format(orig.getReturnedQuantity()) + ").";
            }
            if (error != null) {
                String name = orig == null ? "سطر" : orig.getProductName();
                errors.append(errors.isEmpty() ? "" : "\n").append("\"").append(name).append("\": ").append(error);
                continue;
            }
            ReturnLine l = orig.copy();
            l.setQuantity(QuantityUtil.of(q));
            out.add(l);
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(LINES, errors.toString());
        }
        if (out.isEmpty()) {
            throw new ValidationException(LINES, "أدخل كمية المرتجع لصنف واحد على الأقل.");
        }
        return out;
    }

    /** Checks that need no database; package-private for unit tests. */
    static Validation validateFields(ReturnDocument r) {
        Validation v = new Validation();
        if (r.getOriginalId() == null) {
            v.error(ORIGINAL, r.getKind() == ReturnKind.PURCHASE ? "اختر فاتورة الشراء الأصلية." : "اختر فاتورة البيع الأصلية.");
        }
        if (r.getReason() == null) {
            v.error(REASON, "اختر سبب الإرجاع.");
        }
        v.maxLength(NOTES, r.getNotes(), 500, "الملاحظات");
        FinanceRules.method(v, METHOD, r.getRefundMethod());
        boolean any = false;
        for (ReturnLine l : r.getLines()) {
            BigDecimal q = l.getQuantity();
            if (q == null || q.signum() == 0) {
                continue;
            }
            any = true;
            if (q.signum() < 0) {
                v.error(LINES, "كمية المرتجع لا يمكن أن تكون سالبة.");
            } else if (q.stripTrailingZeros().scale() > QuantityUtil.SCALE) {
                v.error(LINES, "الكمية: الحد الأقصى 3 منازل عشرية.");
            }
        }
        if (!any) {
            v.error(LINES, "أدخل كمية المرتجع لصنف واحد على الأقل.");
        }
        return v;
    }

    private Optional<ReturnDocument> alreadySaved(ReturnDocument r) {
        if (r.getRequestId() == null) {
            return Optional.empty();
        }
        return returnDao.findIdByRequest(null, r.getKind(), r.getRequestId()).flatMap(id -> findById(r.getKind(), id));
    }
}
