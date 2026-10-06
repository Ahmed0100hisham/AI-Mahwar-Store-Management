package com.almahwar.service;

import com.almahwar.dao.AuditLogDao;
import com.almahwar.dao.CashTransactionDao;
import com.almahwar.dao.CustomerDao;
import com.almahwar.dao.DataAccessException;
import com.almahwar.dao.ProductDao;
import com.almahwar.dao.ProductDao.LockedStock;
import com.almahwar.dao.SaleDao;
import com.almahwar.dao.TransactionManager;
import com.almahwar.dao.UnitDao;
import com.almahwar.model.CreditStatus;
import com.almahwar.model.Customer;
import com.almahwar.model.LedgerEntryType;
import com.almahwar.model.MovementType;
import com.almahwar.model.PartyFilter;
import com.almahwar.model.PartyType;
import com.almahwar.model.PaymentType;
import com.almahwar.model.Permission;
import com.almahwar.model.Product;
import com.almahwar.model.ProductFilter.ActiveStatus;
import com.almahwar.model.Sale;
import com.almahwar.model.SaleFilter;
import com.almahwar.model.SaleItem;
import com.almahwar.model.SaleStatus;
import com.almahwar.model.Unit;
import com.almahwar.model.User;
import com.almahwar.service.InsufficientStockException.Shortage;
import com.almahwar.service.PaymentRules.PaymentPlan;
import com.almahwar.util.MoneyUtil;
import com.almahwar.util.QuantityUtil;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.almahwar.service.Validation.trimToNull;

/** Sales and the point of sale on SQL Server; see {@link SaleService} for the posting transaction. */
public class SaleServiceImpl implements SaleService {

    static final String TABLE = "Sales";
    static final String REFERENCE = "SALE";

    private static final int MAX_PRODUCT_RESULTS = 30;

    private final SaleDao saleDao;
    private final CustomerDao customerDao;
    private final ProductDao productDao;
    private final UnitDao unitDao;
    private final StockLedger stockLedger;
    private final AccountLedger accountLedger;
    private final CashTransactionDao cashDao;
    private final AuditLogDao auditLogDao;
    private final SecurityContext security;
    private final com.almahwar.dao.QuotationDao quotationDao;

    public SaleServiceImpl(SaleDao saleDao, CustomerDao customerDao, ProductDao productDao, UnitDao unitDao,
                           StockLedger stockLedger, AccountLedger accountLedger, CashTransactionDao cashDao,
                           AuditLogDao auditLogDao, SecurityContext security) {
        this(saleDao, customerDao, productDao, unitDao, stockLedger, accountLedger, cashDao, auditLogDao, security,
                new com.almahwar.dao.QuotationDao());
    }

    /** @param quotationDao a sale made from a quotation completes that quotation when it is posted */
    public SaleServiceImpl(SaleDao saleDao, CustomerDao customerDao, ProductDao productDao, UnitDao unitDao,
                           StockLedger stockLedger, AccountLedger accountLedger, CashTransactionDao cashDao,
                           AuditLogDao auditLogDao, SecurityContext security, com.almahwar.dao.QuotationDao quotationDao) {
        this.quotationDao = quotationDao;
        this.saleDao = saleDao;
        this.customerDao = customerDao;
        this.productDao = productDao;
        this.unitDao = unitDao;
        this.stockLedger = stockLedger;
        this.accountLedger = accountLedger;
        this.cashDao = cashDao;
        this.auditLogDao = auditLogDao;
        this.security = security;
    }

    // ======================= Reading =======================

    @Override
    public List<Sale> search(SaleFilter filter) {
        security.requirePermission(Permission.SALES_VIEW);
        List<Sale> rows = saleDao.search(filter == null ? SaleFilter.all() : filter, MAX_LIST_ROWS);
        rows.forEach(this::hideIfNeeded);
        return rows;
    }

    @Override
    public Optional<Sale> findById(int saleId) {
        security.requirePermission(Permission.SALES_VIEW);
        return saleDao.findById(saleId).map(this::hideIfNeeded);
    }

    @Override
    public String suggestNumber() {
        security.requirePermission(Permission.SALES_CREATE);
        return saleDao.nextNumber(null);
    }

    @Override
    public Customer walkInCustomer() {
        security.requirePermission(Permission.SALES_CREATE);
        return customerDao.findCashCustomer().map(this::hideBalance)
                .orElseThrow(() -> new ValidationException(CUSTOMER,
                        "العميل النقدي (CASH) غير موجود في قاعدة البيانات؛ شغّل سكربت قاعدة البيانات لإضافته."));
    }

    @Override
    public List<Product> searchProducts(String text) {
        security.requirePermission(Permission.SALES_CREATE);
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Product> found = productDao.search(text.trim(), true);
        List<Product> rows = new ArrayList<>(found.subList(0, Math.min(found.size(), MAX_PRODUCT_RESULTS)));
        rows.forEach(this::hideProductCost);
        return rows;
    }

    @Override
    public Optional<Product> findByScan(String code) {
        security.requirePermission(Permission.SALES_CREATE);
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String c = code.trim();
        Optional<Product> p = productDao.findByBarcode(c);
        if (p.isEmpty()) {
            p = productDao.findByCode(c);
        }
        return p.map(this::hideProductCost);
    }

    @Override
    public List<Customer> searchCustomers(String text) {
        security.requirePermission(Permission.SALES_CREATE);
        return customerDao.search(new PartyFilter(text, ActiveStatus.ACTIVE, false, false)).stream()
                .map(this::hideBalance).toList();
    }

    @Override
    public List<User> cashiers() {
        security.requirePermission(Permission.SALES_VIEW);
        return saleDao.findCashiers();
    }

    private Sale hideIfNeeded(Sale s) {
        if (!security.hasPermission(Permission.SALES_COST_VIEW)) {
            s.setCostTotal(null);
            s.getItems().forEach(i -> i.setUnitCost(null));
        }
        if (!security.hasPermission(Permission.SALES_PROFIT_VIEW)) {
            s.setGrossProfit(null);
        }
        return s;
    }

    private Product hideProductCost(Product p) {
        if (!security.hasPermission(Permission.PRODUCT_COST)) {
            p.setPurchasePrice(null);
        }
        return p;
    }

    private Customer hideBalance(Customer c) {
        if (!security.hasPermission(Permission.CUSTOMER_BALANCE_VIEW)) {
            c.setBalance(null);
            c.setOpeningBalance(null);
            c.setCreditLimit(null);
        }
        return c;
    }

    // ======================= Writing =======================

    @Override
    public Sale saveDraft(Sale sale, PaymentType paymentType) {
        security.requirePermission(Permission.SALES_CREATE);
        int userId = security.currentUser().getUserId();
        if (sale.getSaleId() == null) {
            Optional<Sale> already = alreadySaved(sale);
            if (already.isPresent()) {
                return already.get();
            }
        } else if (sale.getQuotationId() == null || sale.getNotes() == null) {
            // a held draft re-saved from the POS keeps the quotation it came from (and its note)
            Optional<Sale> stored = saleDao.findById(sale.getSaleId());
            if (sale.getQuotationId() == null) {
                sale.setQuotationId(stored.map(Sale::getQuotationId).orElse(null));
            }
            if (sale.getNotes() == null) {
                sale.setNotes(stored.map(Sale::getNotes).orElse(null));
            }
        }
        // a draft moves nothing: a stock shortage is only refused when the sale is posted
        prepare(sale, paymentType, false);

        int id = guard(sale, () -> TransactionManager.inTransaction(con -> {
            if (sale.getSaleId() == null) {
                insertDraft(con, sale, userId);
            } else {
                if (!saleDao.updateDraft(con, sale)) {
                    throw notDraft();
                }
                saleDao.replaceItems(con, sale.getSaleId(), sale.getItems());
            }
            return sale.getSaleId();
        }));
        return findById(id).orElseThrow();
    }

    @Override
    public Sale post(int saleId, boolean overrideCreditLimit) {
        security.requirePermission(Permission.SALES_POST);
        int userId = security.currentUser().getUserId();
        Sale stored = saleDao.findById(saleId)
                .orElseThrow(() -> new ValidationException("saleId", "فاتورة البيع غير موجودة."));
        if (!stored.isDraft()) {
            throw notDraft();
        }
        // Re-check everything: the customer, a product or a price may have changed since the draft was saved
        Validation v = validateFields(stored);
        PaymentRules.check(v, stored.getPaymentType(), stored.getPaymentMethod(), stored.getPaidAmount(),
                stored.getTotalAmount());
        v.throwIfAny();
        checkDiscountPermission(stored);
        validateReferences(stored, v, true);
        v.throwIfAny();

        TransactionManager.inTransaction(con -> {
            applyPosting(con, saleId, userId, overrideCreditLimit);
            return null;
        });
        return findById(saleId).orElseThrow();
    }

    @Override
    public Sale saveAndPost(Sale sale, PaymentType paymentType, boolean overrideCreditLimit) {
        security.requirePermission(Permission.SALES_CREATE);
        security.requirePermission(Permission.SALES_POST);
        int userId = security.currentUser().getUserId();
        if (sale.getSaleId() != null) {
            // a held draft: save the latest cart, then post (two steps, each atomic)
            saveDraft(sale, paymentType);
            return post(sale.getSaleId(), overrideCreditLimit);
        }
        Optional<Sale> already = alreadySaved(sale);
        if (already.isPresent()) {
            return already.get();
        }
        prepare(sale, paymentType, true);

        int id = guard(sale, () -> TransactionManager.inTransaction(con -> {
            insertDraft(con, sale, userId);
            applyPosting(con, sale.getSaleId(), userId, overrideCreditLimit);
            return sale.getSaleId();
        }));
        return findById(id).orElseThrow();
    }

    @Override
    public void cancelDraft(int saleId) {
        security.requirePermission(Permission.SALES_CREATE);
        int userId = security.currentUser().getUserId();
        Sale stored = saleDao.findById(saleId)
                .orElseThrow(() -> new ValidationException("saleId", "فاتورة البيع غير موجودة."));
        TransactionManager.inTransaction(con -> {
            if (!saleDao.cancelDraft(con, saleId)) {
                throw new ValidationException("saleId", stored.getStatus() == SaleStatus.POSTED
                        ? "لا يمكن إلغاء فاتورة معتمدة؛ يتم تصحيحها لاحقًا عن طريق مرتجع المبيعات."
                        : "الفاتورة ملغاة مسبقًا.");
            }
            auditLogDao.log(con, userId, AuditLogDao.CANCEL_SALE, TABLE, String.valueOf(saleId),
                    "إلغاء مسودة مبيعات " + stored.getSaleNo());
            return null;
        });
    }

    // ======================= Posting (inside the caller's transaction) =======================

    /**
     * Applies a DRAFT sale to stock, customer ledger and cash, then marks it POSTED. Runs on the caller's
     * connection: any failure rolls everything back. Lock order (customer, then products by id) is the
     * same for every sale, so concurrent sales wait for each other instead of deadlocking.
     */
    private void applyPosting(Connection con, int saleId, int userId, boolean overrideCreditLimit) {
        // 1-2) lock the sale; only a DRAFT can be posted, and only once
        SaleStatus status = saleDao.lockStatus(con, saleId)
                .orElseThrow(() -> new ValidationException("saleId", "فاتورة البيع غير موجودة."));
        if (status != SaleStatus.DRAFT) {
            throw notDraft();
        }
        Sale s = saleDao.findById(con, saleId).orElseThrow();
        String label = "فاتورة مبيعات " + s.getSaleNo();
        BigDecimal remaining = s.getRemainingAmount();

        // 3-4) customer and credit limit, with the customer row locked
        Customer customer = null;
        if (s.isWalkIn()) {
            if (remaining.signum() > 0) {
                throw walkInMustPay();
            }
        } else {
            customer = customerDao.lockForUpdate(con, s.getCustomerId())
                    .orElseThrow(() -> new ValidationException(CUSTOMER, "العميل غير موجود."));
            if (!customer.isActive()) {
                throw new ValidationException(CUSTOMER, "العميل \"" + customer.getName() + "\" غير نشط.");
            }
            if (remaining.signum() > 0) {
                checkCreditLimit(con, customer, s, remaining, overrideCreditLimit, userId);
            }
        }

        // 5-6) lock the products and check them again: active, price, stock
        Map<Integer, LockedStock> stock = productDao.lockForStockChange(con,
                        s.getItems().stream().map(SaleItem::getProductId).toList()).stream()
                .collect(Collectors.toMap(LockedStock::productId, Function.identity()));
        Map<Integer, BigDecimal> requested = new LinkedHashMap<>();
        for (SaleItem i : s.getItems()) {
            requested.merge(i.getProductId(), i.getQuantity(), BigDecimal::add);
        }
        List<Shortage> shortages = new ArrayList<>();
        for (Map.Entry<Integer, BigDecimal> r : requested.entrySet()) {
            LockedStock p = stock.get(r.getKey());
            if (p == null) {
                throw new ValidationException(ITEMS, "أحد الأصناف لم يعد موجودًا.");
            }
            if (!p.active()) {
                throw new ValidationException(ITEMS, "الصنف \"" + p.nameAr() + "\" معطّل ولا يمكن بيعه.");
            }
            if (r.getValue().compareTo(p.quantity()) > 0) {
                shortages.add(new Shortage(p.productId(), p.nameAr(), QuantityUtil.of(p.quantity()),
                        QuantityUtil.of(r.getValue())));
            }
        }
        if (!shortages.isEmpty()) {
            throw new InsufficientStockException(shortages);
        }
        List<String[]> overrides = new ArrayList<>();
        boolean mayOverride = security.hasPermission(Permission.SALES_PRICE_OVERRIDE);
        Map<Integer, BigDecimal> agreed = s.getQuotationId() == null ? Map.of()
                : quotationDao.agreedPrices(con, s.getQuotationId());
        for (SaleItem i : s.getItems()) {
            LockedStock p = stock.get(i.getProductId());
            BigDecimal list = MoneyUtil.of(s.getSaleType().priceOf(p.salePrice(), p.wholesalePrice()));
            if (i.getUnitPrice().compareTo(list) != 0) {
                if (!mayOverride && !isAgreedPrice(agreed, i.getProductId(), i.getUnitPrice())) {
                    throw priceNotAllowed(p.nameAr(), list);
                }
                overrides.add(new String[]{String.valueOf(i.getSaleItemId()), p.productCode(), p.nameAr(),
                        MoneyUtil.format(list), MoneyUtil.format(i.getUnitPrice())});
            }
        }

        // 7-10) historical unit cost (the product's current cost under the costing policy), then stock out
        BigDecimal costTotal = BigDecimal.ZERO;
        List<SaleItem> lines = s.getItems().stream()
                .sorted(java.util.Comparator.comparing(SaleItem::getProductId)).toList();   // same order as the locks
        for (SaleItem i : lines) {
            BigDecimal unitCost = MoneyUtil.of(stock.get(i.getProductId()).purchasePrice());
            saleDao.setItemCost(con, i.getSaleItemId(), unitCost);
            i.setUnitCost(unitCost);
            costTotal = costTotal.add(i.getCostTotal());
            stockLedger.post(con, i.getProductId(), MovementType.SALE, i.getQuantity(), unitCost, REFERENCE, saleId,
                    label, userId);
        }

        // 11) customer account: the invoice adds to the debt, what is paid now takes it off again
        if (customer != null) {
            if (s.getTotalAmount().signum() > 0) {
                accountLedger.post(con, PartyType.CUSTOMER, customer.getCustomerId(), LedgerEntryType.SALE,
                        s.getTotalAmount(), REFERENCE, saleId, s.getSaleNo(), label, userId);
            }
            if (s.getPaidAmount().signum() > 0) {
                accountLedger.post(con, PartyType.CUSTOMER, customer.getCustomerId(), LedgerEntryType.PAYMENT,
                        s.getPaidAmount().negate(), REFERENCE, saleId, s.getSaleNo(),
                        "المدفوع مع " + label + " (" + s.getPaymentMethod().getLabelAr() + ")", userId);
            }
        }

        // 12) the money enters the cash box / bank
        if (s.getPaidAmount().signum() > 0) {
            cashDao.insert(con, CashTransactionDao.IN, s.getPaidAmount(), s.getPaymentMethod(), REFERENCE, saleId,
                    "تحصيل فاتورة مبيعات " + s.getSaleNo(), userId);
        }

        // 13-15) totals and profit, DRAFT → POSTED, audit
        BigDecimal cost = MoneyUtil.of(costTotal);
        if (!saleDao.markPosted(con, saleId, userId, cost)) {
            throw notDraft();
        }
        // a sale made from a quotation completes it here, in the same transaction: ACCEPTED → CONVERTED
        if (s.getQuotationId() != null) {
            if (!quotationDao.markConverted(con, s.getQuotationId(), saleId)) {
                throw new ValidationException("quotationId", "عرض السعر " + s.getQuotationNo()
                        + " انتهت صلاحيته أو لم يعد في حالة \"مقبول\"؛ لا يمكن اعتماد الفاتورة المرتبطة به."
                        + " ألغِ المسودة أو أنشئ عرضًا جديدًا.");
            }
            auditLogDao.log(con, userId, AuditLogDao.QUOTATION_CONVERTED, "Quotations", String.valueOf(s.getQuotationId()),
                    "تحويل عرض السعر " + s.getQuotationNo() + " إلى " + label + " بعد اعتمادها");
        }
        String request = s.getRequestId() == null ? "" : " (طلب " + s.getRequestId() + ")";
        for (String[] o : overrides) {
            auditLogDao.log(con, userId, AuditLogDao.PRICE_OVERRIDE, "Sale_Items", o[0],
                    "{\"sale\":\"" + s.getSaleNo() + "\",\"product\":\"" + json(o[1]) + "\",\"price\":" + o[3] + "}",
                    "{\"sale\":\"" + s.getSaleNo() + "\",\"product\":\"" + json(o[1]) + "\",\"price\":" + o[4] + "}",
                    "تعديل سعر \"" + o[2] + "\" (" + o[1] + ") من " + o[3] + " إلى " + o[4] + " في " + label + request
                            + (s.getQuotationNo() == null ? "" : " — سعر عرض السعر " + s.getQuotationNo()));
        }
        auditLogDao.log(con, userId, AuditLogDao.POST_SALE, TABLE, String.valueOf(saleId),
                "اعتماد " + label + " للعميل " + s.getCustomerName() + " بإجمالي " + MoneyUtil.format(s.getTotalAmount())
                        + " ومدفوع " + MoneyUtil.format(s.getPaidAmount()) + " وتكلفة " + MoneyUtil.format(cost));
    }

    private void checkCreditLimit(Connection con, Customer c, Sale s, BigDecimal remaining, boolean override,
                                  int userId) {
        CreditStatus credit = CreditStatus.of(c.getCreditLimit(), c.getBalance());
        if (credit.allows(remaining)) {
            return;
        }
        boolean mayOverride = security.hasPermission(Permission.CUSTOMER_CREDIT_OVERRIDE);
        BigDecimal after = MoneyUtil.of(credit.currentDebt().add(remaining));
        if (!override || !mayOverride) {
            String message;
            if (credit.creditLimit().signum() == 0) {
                message = "العميل \"" + c.getName() + "\" ليس له حد ائتمان (بيع نقدي فقط)؛ لا يمكن ترك مبلغ متبقٍ عليه.";
            } else if (security.hasPermission(Permission.CUSTOMER_BALANCE_VIEW)) {
                message = "تتجاوز الفاتورة حد ائتمان العميل \"" + c.getName() + "\": الرصيد الحالي "
                        + MoneyUtil.format(credit.currentDebt()) + " + المتبقي " + MoneyUtil.format(remaining) + " = "
                        + MoneyUtil.format(after) + "، والحد المسموح " + MoneyUtil.format(credit.creditLimit()) + ".";
            } else {
                message = "المبلغ المتبقي يتجاوز حد ائتمان العميل \"" + c.getName()
                        + "\"؛ حصّل مبلغًا أكبر أو اطلب موافقة المدير.";
            }
            throw new CreditLimitExceededException(message, mayOverride);
        }
        auditLogDao.log(con, userId, AuditLogDao.CREDIT_OVERRIDE, "Customers", String.valueOf(c.getCustomerId()),
                "{\"balance\":" + credit.currentDebt() + ",\"creditLimit\":" + credit.creditLimit() + "}",
                "{\"sale\":\"" + s.getSaleNo() + "\",\"remaining\":" + remaining + ",\"balanceAfter\":" + after + "}",
                "تجاوز حد ائتمان العميل " + c.getName() + " في فاتورة " + s.getSaleNo() + ": الرصيد بعد البيع "
                        + MoneyUtil.format(after) + " والحد " + MoneyUtil.format(credit.creditLimit()));
    }

    private static String json(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void insertDraft(Connection con, Sale s, int userId) {
        s.setSaleNo(saleDao.nextNumber(con));
        s.setUserId(userId);
        saleDao.insert(con, s);
        saleDao.replaceItems(con, s.getSaleId(), s.getItems());
        auditLogDao.log(con, userId, AuditLogDao.CREATE_SALE, TABLE, String.valueOf(s.getSaleId()),
                "إنشاء فاتورة مبيعات " + s.getSaleNo() + " بإجمالي " + MoneyUtil.format(s.getTotalAmount()));
    }

    // ======================= Validation =======================

    /** Normalises, computes totals, resolves the payment and validates; throws on any error. */
    private void prepare(Sale s, PaymentType type, boolean checkStock) {
        s.setNotes(trimToNull(s.getNotes()));
        if (s.getDiscountAmount() == null) {
            s.setDiscountAmount(MoneyUtil.ZERO);
        }
        for (SaleItem i : s.getItems()) {
            if (i.getDiscountAmount() == null) {
                i.setDiscountAmount(MoneyUtil.ZERO);
            }
        }
        Validation v = validateFields(s);
        PaymentRules.check(v, type, s.getPaymentMethod(), s.getPaidAmount(), s.getTotalAmount());
        v.throwIfAny();
        checkDiscountPermission(s);

        PaymentPlan plan = PaymentRules.plan(type, s.getPaymentMethod(), s.getPaidAmount(), s.getTotalAmount());
        s.setPaymentMethod(plan.method());
        s.setPaidAmount(plan.paid());
        // store exact 3-decimal values
        s.setDiscountAmount(MoneyUtil.of(s.getDiscountAmount()));
        for (SaleItem i : s.getItems()) {
            i.setQuantity(QuantityUtil.of(i.getQuantity()));
            i.setUnitPrice(MoneyUtil.of(i.getUnitPrice()));
            i.setDiscountAmount(MoneyUtil.of(i.getDiscountAmount()));
        }
        s.recalculate();

        validateReferences(s, v, checkStock);
        v.throwIfAny();
    }

    /** Checks that need no database (also recalculates the totals); package-private for unit tests. */
    static Validation validateFields(Sale s) {
        Validation v = new Validation();
        if (s.getCustomerId() == null) {
            v.error(CUSTOMER, "اختر العميل.");
        }
        if (s.getSaleType() == null) {
            v.error(SALE_TYPE, "اختر نوع البيع (تجزئة أو جملة).");
        }
        v.maxLength(NOTES, s.getNotes(), 500, "الملاحظات");
        if (s.getItems().isEmpty()) {
            v.error(ITEMS, "السلة فارغة؛ أضف صنفًا واحدًا على الأقل.");
            return v;
        }
        StringBuilder lines = new StringBuilder();
        int n = 0;
        for (SaleItem i : s.getItems()) {
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
        s.recalculate();
        BigDecimal discount = s.getDiscountAmount() == null ? BigDecimal.ZERO : s.getDiscountAmount();
        if (discount.signum() < 0) {
            v.error(DISCOUNT, "الخصم لا يمكن أن يكون سالبًا.");
        } else if (discount.stripTrailingZeros().scale() > MoneyUtil.SCALE) {
            v.error(DISCOUNT, "الخصم: الحد الأقصى 3 منازل عشرية.");
        } else if (discount.compareTo(s.getSubtotal()) > 0) {
            v.error(DISCOUNT, "الخصم أكبر من مجموع الأصناف؛ لا يمكن أن يكون إجمالي الفاتورة سالبًا.");
        }
        return v;
    }

    /** @return an Arabic error for one line, or {@code null} */
    static String itemError(SaleItem i) {
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
        BigDecimal price = i.getUnitPrice();
        if (price == null || price.signum() < 0) {
            return "سعر البيع لا يمكن أن يكون سالبًا.";
        }
        if (price.stripTrailingZeros().scale() > MoneyUtil.SCALE) {
            return "سعر البيع: الحد الأقصى 3 منازل عشرية.";
        }
        BigDecimal discount = i.getDiscountAmount() == null ? BigDecimal.ZERO : i.getDiscountAmount();
        if (discount.signum() < 0) {
            return "خصم السطر لا يمكن أن يكون سالبًا.";
        }
        if (discount.stripTrailingZeros().scale() > MoneyUtil.SCALE) {
            return "خصم السطر: الحد الأقصى 3 منازل عشرية.";
        }
        if (discount.compareTo(qty.multiply(price)) > 0) {
            return "خصم السطر أكبر من قيمته؛ لا يمكن أن يكون إجمالي السطر سالبًا.";
        }
        return null;
    }

    /** A user without SALES_DISCOUNT cannot give any discount (the screen locks the fields too). */
    private void checkDiscountPermission(Sale s) {
        if (security.hasPermission(Permission.SALES_DISCOUNT)) {
            return;
        }
        boolean discounted = s.getDiscountAmount() != null && s.getDiscountAmount().signum() != 0
                || s.getItems().stream().anyMatch(i -> i.getDiscountAmount() != null && i.getDiscountAmount().signum() != 0);
        if (discounted) {
            throw new ValidationException(DISCOUNT, "ليس لديك صلاحية منح الخصومات؛ احذف الخصم أو اطلب مستخدمًا مصرحًا له.");
        }
    }

    /**
     * Customer, walk-in rule, products, units, list prices and (when {@code checkStock}) stock — database, no locks.
     * A sale made from a quotation may use the quotation's agreed price without the price-override permission: that
     * price was already authorised when the quotation was made.
     */
    private void validateReferences(Sale s, Validation v, boolean checkStock) {
        Map<Integer, BigDecimal> agreed = s.getQuotationId() == null ? Map.of()
                : quotationDao.agreedPrices(null, s.getQuotationId());
        Optional<Customer> customer = customerDao.findById(s.getCustomerId());
        if (customer.isEmpty()) {
            v.error(CUSTOMER, "العميل غير موجود.");
        } else if (!customer.get().isActive()) {
            v.error(CUSTOMER, "العميل \"" + customer.get().getName() + "\" غير نشط؛ اختر عميلًا آخر أو أعد تفعيله.");
        } else if (customer.get().isCashCustomer() && s.getRemainingAmount().signum() > 0) {
            v.error(PAYMENT_TYPE, walkInMustPay().getMessage());
        }

        boolean mayOverride = security.hasPermission(Permission.SALES_PRICE_OVERRIDE);
        Map<Integer, BigDecimal> requested = new LinkedHashMap<>();
        Map<Integer, Product> products = new LinkedHashMap<>();
        StringBuilder lines = new StringBuilder();
        int n = 0;
        for (SaleItem i : s.getItems()) {
            n++;
            Optional<Product> product = productDao.findById(i.getProductId());
            String error = null;
            if (product.isEmpty()) {
                error = "الصنف غير موجود.";
            } else if (!product.get().isActive()) {
                error = "الصنف \"" + product.get().getNameAr() + "\" معطّل ولا يمكن بيعه.";
            } else {
                Product p = product.get();
                Unit unit = unitDao.findById(p.getUnitId()).orElseThrow();
                BigDecimal list = MoneyUtil.of(s.getSaleType().priceOf(p));
                if (!unit.isAllowsDecimal() && i.getQuantity().stripTrailingZeros().scale() > 0) {
                    error = "الوحدة \"" + unit.getNameAr() + "\" لا تقبل الكسور.";
                } else if (!mayOverride && i.getUnitPrice().compareTo(list) != 0
                        && !isAgreedPrice(agreed, p.getProductId(), i.getUnitPrice())) {
                    error = priceNotAllowed(p.getNameAr(), list).getMessage();
                }
                requested.merge(p.getProductId(), i.getQuantity(), BigDecimal::add);
                products.put(p.getProductId(), p);
            }
            if (error != null) {
                lines.append(lines.isEmpty() ? "" : "\n").append("السطر ").append(n).append(": ").append(error);
            }
        }
        if (!lines.isEmpty()) {
            v.error(ITEMS, lines.toString());
            return;
        }
        if (!checkStock) {
            return;
        }
        // early stock check for a clear message; the posting transaction checks again with the rows locked
        List<Shortage> shortages = new ArrayList<>();
        requested.forEach((id, qty) -> {
            Product p = products.get(id);
            if (qty.compareTo(p.getQuantity()) > 0) {
                shortages.add(new Shortage(id, p.getNameAr(), QuantityUtil.of(p.getQuantity()), QuantityUtil.of(qty)));
            }
        });
        if (!shortages.isEmpty() && !v.has(CUSTOMER) && !v.has(PAYMENT_TYPE)) {
            throw new InsufficientStockException(shortages);
        }
    }

    private static boolean isAgreedPrice(Map<Integer, BigDecimal> agreed, int productId, BigDecimal price) {
        BigDecimal a = agreed.get(productId);
        return a != null && a.compareTo(price) == 0;
    }

    private static ValidationException walkInMustPay() {
        return new ValidationException(PAYMENT_TYPE,
                "البيع للعميل النقدي يجب أن يُدفع بالكامل؛ لاختيار الآجل أو الدفعة الجزئية اختر عميلًا مسجلًا.");
    }

    private static ValidationException priceNotAllowed(String productName, BigDecimal listPrice) {
        return new ValidationException(ITEMS, "سعر \"" + productName + "\" يجب أن يكون سعر القائمة "
                + MoneyUtil.format(listPrice) + "؛ ليس لديك صلاحية تعديل سعر البيع.");
    }

    // ======================= Duplicate protection =======================

    /** A retried request (same request id) returns what the first one saved instead of saving again. */
    private Optional<Sale> alreadySaved(Sale s) {
        if (s.getRequestId() == null) {
            return Optional.empty();
        }
        return saleDao.findIdByRequest(s.getRequestId()).flatMap(this::findById);
    }

    private int guard(Sale s, java.util.function.Supplier<Integer> save) {
        boolean isNew = s.getSaleId() == null;
        try {
            return save.get();
        } catch (RuntimeException e) {
            if (isNew) {
                // the transaction rolled back: forget the id/number it had assigned, so a retry starts clean
                s.setSaleId(null);
                s.setSaleNo(null);
                s.getItems().forEach(i -> {
                    i.setSaleItemId(null);
                    i.setSaleId(null);
                });
            }
            throw translate(s, e);
        }
    }

    private RuntimeException translate(Sale s, RuntimeException ex) {
        if (ex instanceof DataAccessException e) {
            if (e.violates("UX_Sales_request") && s.getRequestId() != null) {
                return new ValidationException("requestId",
                        "تم حفظ هذه الفاتورة مسبقًا (ضغطة مكررة)؛ افتح قائمة المبيعات لعرضها.");
            }
            if (e.violates("UQ_Sales_invoice_no")) {
                return new ValidationException("saleNo", "رقم الفاتورة مستخدم؛ أعد المحاولة.");
            }
        }
        return ex;
    }

    private static ValidationException notDraft() {
        return new ValidationException("saleId",
                "الفاتورة معتمدة أو ملغاة مسبقًا؛ لا يمكن تعديلها أو اعتمادها مرة أخرى.");
    }
}
