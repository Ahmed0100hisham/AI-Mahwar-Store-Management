package com.almahwar.service;

import com.almahwar.dao.AuditLogDao;
import com.almahwar.dao.CustomerDao;
import com.almahwar.dao.DataAccessException;
import com.almahwar.dao.ProductDao;
import com.almahwar.dao.QuotationDao;
import com.almahwar.dao.SaleDao;
import com.almahwar.dao.TransactionManager;
import com.almahwar.dao.UnitDao;
import com.almahwar.model.Customer;
import com.almahwar.model.PartyFilter;
import com.almahwar.model.ProductFilter.ActiveStatus;
import com.almahwar.model.PaymentType;
import com.almahwar.model.Permission;
import com.almahwar.model.Product;
import com.almahwar.model.Quotation;
import com.almahwar.model.QuotationConversion;
import com.almahwar.model.QuotationFilter;
import com.almahwar.model.QuotationItem;
import com.almahwar.model.QuotationStatus;
import com.almahwar.model.Sale;
import com.almahwar.model.SaleItem;
import com.almahwar.model.Unit;
import com.almahwar.util.MoneyUtil;
import com.almahwar.util.QuantityUtil;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.almahwar.service.Validation.trimToNull;

/** Quotations on SQL Server; see {@link QuotationService}. Never touches stock, accounts or cash. */
public class QuotationServiceImpl implements QuotationService {

    static final String TABLE = "Quotations";

    private final QuotationDao quotationDao;
    private final CustomerDao customerDao;
    private final ProductDao productDao;
    private final UnitDao unitDao;
    private final SaleDao saleDao;
    private final SaleService saleService;
    private final AuditLogDao auditLogDao;
    private final SecurityContext security;

    public QuotationServiceImpl(QuotationDao quotationDao, CustomerDao customerDao, ProductDao productDao, UnitDao unitDao,
                                SaleDao saleDao, SaleService saleService, AuditLogDao auditLogDao, SecurityContext security) {
        this.quotationDao = quotationDao;
        this.customerDao = customerDao;
        this.productDao = productDao;
        this.unitDao = unitDao;
        this.saleDao = saleDao;
        this.saleService = saleService;
        this.auditLogDao = auditLogDao;
        this.security = security;
    }

    // ======================= Reading =======================

    @Override
    public List<Quotation> search(QuotationFilter filter) {
        security.requirePermission(Permission.QUOTATIONS_VIEW);
        expireOverdue();
        return quotationDao.search(filter == null ? QuotationFilter.all() : filter, MAX_LIST_ROWS);
    }

    @Override
    public Optional<Quotation> findById(int quotationId) {
        security.requirePermission(Permission.QUOTATIONS_VIEW);
        expireOverdue();
        return quotationDao.findById(quotationId);
    }

    @Override
    public String suggestNumber() {
        security.requirePermission(Permission.QUOTATIONS_VIEW);
        return quotationDao.nextNumber(null);
    }

    @Override
    public List<Customer> activeCustomers() {
        security.requireAnyPermission(Permission.QUOTATIONS_CREATE, Permission.QUOTATIONS_EDIT);
        return customerDao.search(new PartyFilter(null, ActiveStatus.ACTIVE, false, false)).stream().map(c -> {
            if (!security.hasPermission(Permission.CUSTOMER_BALANCE_VIEW)) {
                c.setBalance(null);
                c.setOpeningBalance(null);
                c.setCreditLimit(null);
            }
            return c;
        }).toList();
    }

    @Override
    public List<Product> searchProducts(String text) {
        security.requireAnyPermission(Permission.QUOTATIONS_CREATE, Permission.QUOTATIONS_EDIT);
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Product> found = productDao.search(text.trim(), true);
        List<Product> rows = new ArrayList<>(found.subList(0, Math.min(found.size(), 30)));
        if (!security.hasPermission(Permission.PRODUCT_COST)) {
            rows.forEach(p -> p.setPurchasePrice(null));
        }
        return rows;
    }

    /** A passed validity date turns open quotations into EXPIRED (no scheduler needed); each one is audited. */
    private void expireOverdue() {
        for (String no : quotationDao.expireOverdue()) {
            auditLogDao.log(null, AuditLogDao.QUOTATION_EXPIRED, TABLE, no, "انتهت صلاحية عرض السعر " + no);
        }
    }

    // ======================= Saving drafts =======================

    @Override
    public Quotation save(Quotation q) {
        boolean adding = q.getQuotationId() == null;
        security.requirePermission(adding ? Permission.QUOTATIONS_CREATE : Permission.QUOTATIONS_EDIT);
        int userId = security.currentUser().getUserId();
        if (adding && q.getRequestId() != null) {
            Optional<Quotation> already = quotationDao.findIdByRequest(null, q.getRequestId()).flatMap(quotationDao::findById);
            if (already.isPresent()) {
                return already.get();
            }
        }
        normalise(q);
        Validation v = validateFields(q, LocalDate.now());
        v.throwIfAny();
        checkDiscountPermission(q);
        List<String[]> overrides = validateReferences(q, v);
        v.throwIfAny();

        try {
            Integer id = TransactionManager.inTransaction(con -> {
                if (adding) {
                    q.setQuotationNo(quotationDao.nextNumber(con));   // locks the number range until commit
                    if (q.getRequestId() != null) {
                        Optional<Integer> existing = quotationDao.findIdByRequest(con, q.getRequestId());
                        if (existing.isPresent()) {
                            return existing.get();   // a concurrent retry of this request saved it first
                        }
                    }
                    q.setUserId(userId);
                    quotationDao.insert(con, q);
                } else {
                    QuotationStatus status = quotationDao.lockStatus(con, q.getQuotationId())
                            .orElseThrow(() -> new ValidationException(STATUS, "عرض السعر غير موجود."));
                    if (!status.isEditable() || !quotationDao.updateDraft(con, q)) {
                        throw new ValidationException(STATUS, "لا يمكن تعديل عرض سعر في حالة \"" + status.getLabelAr()
                                + "\"؛ التعديل للمسودات فقط.");
                    }
                }
                quotationDao.replaceItems(con, q.getQuotationId(), q.getItems());
                auditLogDao.log(con, userId, adding ? AuditLogDao.QUOTATION_CREATED : AuditLogDao.QUOTATION_UPDATED, TABLE,
                        String.valueOf(q.getQuotationId()), (adding ? "إنشاء" : "تعديل") + " عرض السعر " + q.getQuotationNo()
                                + " بإجمالي " + MoneyUtil.format(q.getTotalAmount()) + " صالح حتى " + q.getValidUntil());
                for (String[] o : overrides) {
                    auditLogDao.log(con, userId, AuditLogDao.QUOTATION_PRICE_OVERRIDE, "Quotation_Items",
                            String.valueOf(q.getQuotationId()), "{\"product\":\"" + json(o[0]) + "\",\"price\":" + o[2] + "}",
                            "{\"quotation\":\"" + q.getQuotationNo() + "\",\"product\":\"" + json(o[0]) + "\",\"price\":" + o[3] + "}",
                            "سعر يدوي في عرض السعر " + q.getQuotationNo() + ": \"" + o[1] + "\" من " + o[2] + " إلى " + o[3]);
                }
                if (hasDiscount(q)) {
                    auditLogDao.log(con, userId, AuditLogDao.QUOTATION_DISCOUNT, TABLE, String.valueOf(q.getQuotationId()),
                            "خصومات عرض السعر " + q.getQuotationNo() + ": خصم الأسطر " + MoneyUtil.format(lineDiscounts(q))
                                    + "، خصم العرض " + MoneyUtil.format(q.getDiscountAmount()));
                }
                return q.getQuotationId();
            });
            return quotationDao.findById(id).orElseThrow();
        } catch (RuntimeException e) {
            if (adding) {
                q.setQuotationId(null);
                q.setQuotationNo(null);
                q.getItems().forEach(i -> i.setQuotationItemId(null));
                if (e instanceof DataAccessException dae && q.getRequestId() != null && dae.violates("UX_Quotations_request")) {
                    return quotationDao.findIdByRequest(null, q.getRequestId()).flatMap(quotationDao::findById).orElseThrow(() -> e);
                }
            }
            throw e;
        }
    }

    private static void normalise(Quotation q) {
        q.setNotes(trimToNull(q.getNotes()));
        q.setTerms(trimToNull(q.getTerms()));
        q.setProspectName(trimToNull(q.getProspectName()));
        q.setProspectPhone(trimToNull(q.getProspectPhone()));
        if (q.getDiscountAmount() == null) {
            q.setDiscountAmount(MoneyUtil.ZERO);
        }
        for (QuotationItem i : q.getItems()) {
            if (i.getDiscountAmount() == null) {
                i.setDiscountAmount(MoneyUtil.ZERO);
            }
        }
    }

    /** Checks that need no database (also recalculates the totals); package-private for unit tests. */
    static Validation validateFields(Quotation q, LocalDate today) {
        Validation v = new Validation();
        if (q.getCustomerId() == null) {
            v.error(CUSTOMER, "اختر العميل.");
        }
        if (q.getPriceType() == null) {
            v.error("priceType", "اختر نوع التسعير (تجزئة أو جملة).");
        }
        if (q.getValidUntil() == null) {
            v.error(VALID_UNTIL, "حدد تاريخ صلاحية العرض.");
        } else if (q.getValidUntil().isBefore(today)) {
            v.error(VALID_UNTIL, "تاريخ الصلاحية لا يمكن أن يكون قبل تاريخ العرض.");
        }
        v.maxLength(NOTES, q.getNotes(), 500, "الملاحظات");
        v.maxLength(TERMS, q.getTerms(), 1000, "الشروط");
        v.maxLength(PROSPECT_NAME, q.getProspectName(), 150, "اسم العميل");
        v.maxLength(PROSPECT_PHONE, q.getProspectPhone(), 20, "الهاتف");
        if (q.getItems().isEmpty()) {
            v.error(ITEMS, "أضف صنفًا واحدًا على الأقل.");
            return v;
        }
        StringBuilder lines = new StringBuilder();
        int n = 0;
        for (QuotationItem i : q.getItems()) {
            n++;
            String error = SaleServiceImpl.itemError(i.toSaleItem());   // the same line rules as a sale
            if (error != null) {
                lines.append(lines.isEmpty() ? "" : "\n").append("السطر ").append(n).append(" (")
                        .append(i.getProductName() == null ? "صنف" : i.getProductName()).append("): ").append(error);
            }
        }
        if (!lines.isEmpty()) {
            v.error(ITEMS, lines.toString());
            return v;
        }
        q.recalculate();
        BigDecimal discount = q.getDiscountAmount();
        if (discount.signum() < 0) {
            v.error(DISCOUNT, "الخصم لا يمكن أن يكون سالبًا.");
        } else if (discount.stripTrailingZeros().scale() > MoneyUtil.SCALE) {
            v.error(DISCOUNT, "الخصم: الحد الأقصى 3 منازل عشرية.");
        } else if (discount.compareTo(q.getSubtotal()) > 0) {
            v.error(DISCOUNT, "الخصم أكبر من مجموع الأصناف؛ لا يمكن أن يكون إجمالي العرض سالبًا.");
        }
        return v;
    }

    private static boolean hasDiscount(Quotation q) {
        return q.getDiscountAmount().signum() != 0 || lineDiscounts(q).signum() != 0;
    }

    private static BigDecimal lineDiscounts(Quotation q) {
        return q.getItems().stream().map(QuotationItem::getDiscountAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void checkDiscountPermission(Quotation q) {
        if (hasDiscount(q) && !security.hasPermission(Permission.QUOTATIONS_DISCOUNT)) {
            throw new ValidationException(DISCOUNT, "ليس لديك صلاحية منح خصومات في عروض الأسعار.");
        }
    }

    /**
     * Customer, products, units and prices; stores exact 3-decimal values.
     *
     * @return the lines quoted at a price other than the list price: {code, name, list, price}
     */
    private List<String[]> validateReferences(Quotation q, Validation v) {
        Optional<Customer> customer = customerDao.findById(q.getCustomerId());
        if (customer.isEmpty()) {
            v.error(CUSTOMER, "العميل غير موجود.");
        } else if (!customer.get().isActive()) {
            v.error(CUSTOMER, "العميل \"" + customer.get().getName() + "\" غير نشط؛ اختر عميلًا آخر أو أعد تفعيله.");
        } else if (!customer.get().isCashCustomer()) {
            q.setProspectName(null);   // a registered customer is printed with its own name and phone
            q.setProspectPhone(null);
        }
        boolean mayOverride = security.hasPermission(Permission.QUOTATIONS_PRICE_OVERRIDE);
        List<String[]> overrides = new ArrayList<>();
        StringBuilder lines = new StringBuilder();
        int n = 0;
        for (QuotationItem i : q.getItems()) {
            n++;
            i.setQuantity(QuantityUtil.of(i.getQuantity()));
            i.setUnitPrice(MoneyUtil.of(i.getUnitPrice()));
            i.setDiscountAmount(MoneyUtil.of(i.getDiscountAmount()));
            Optional<Product> product = productDao.findById(i.getProductId());
            String error = null;
            if (product.isEmpty()) {
                error = "الصنف غير موجود.";
            } else if (!product.get().isActive()) {
                error = "الصنف \"" + product.get().getNameAr() + "\" معطّل.";
            } else {
                Product p = product.get();
                Unit unit = unitDao.findById(p.getUnitId()).orElseThrow();
                BigDecimal list = MoneyUtil.of(q.getPriceType().priceOf(p));
                if (!unit.isAllowsDecimal() && i.getQuantity().stripTrailingZeros().scale() > 0) {
                    error = "الوحدة \"" + unit.getNameAr() + "\" لا تقبل الكسور.";
                } else if (i.getUnitPrice().compareTo(list) != 0) {
                    if (!mayOverride) {
                        error = "السعر يجب أن يكون سعر القائمة " + MoneyUtil.format(list) + "؛ ليس لديك صلاحية تعديل الأسعار.";
                    } else {
                        overrides.add(new String[]{p.getProductCode(), p.getNameAr(), MoneyUtil.format(list),
                                MoneyUtil.format(i.getUnitPrice())});
                    }
                }
            }
            if (error != null) {
                lines.append(lines.isEmpty() ? "" : "\n").append("السطر ").append(n).append(": ").append(error);
            }
        }
        if (!lines.isEmpty()) {
            v.error(ITEMS, lines.toString());
        }
        q.setDiscountAmount(MoneyUtil.of(q.getDiscountAmount()));
        q.recalculate();
        return overrides;
    }

    private static String json(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ======================= Status changes =======================

    @Override
    public Quotation send(int quotationId) {
        security.requirePermission(Permission.QUOTATIONS_SEND);
        return transition(quotationId, QuotationStatus.SENT, null, AuditLogDao.QUOTATION_SENT, "إرسال");
    }

    @Override
    public Quotation accept(int quotationId, String note) {
        security.requirePermission(Permission.QUOTATIONS_ACCEPT);
        return transition(quotationId, QuotationStatus.ACCEPTED, note, AuditLogDao.QUOTATION_ACCEPTED, "قبول");
    }

    @Override
    public Quotation reject(int quotationId, String note) {
        security.requirePermission(Permission.QUOTATIONS_ACCEPT);
        return transition(quotationId, QuotationStatus.REJECTED, note, AuditLogDao.QUOTATION_REJECTED, "رفض");
    }

    @Override
    public Quotation reopen(int quotationId) {
        security.requirePermission(Permission.QUOTATIONS_EDIT);
        return transition(quotationId, QuotationStatus.DRAFT, null, AuditLogDao.QUOTATION_REOPENED, "إعادة فتح للتعديل");
    }

    private Quotation transition(int quotationId, QuotationStatus target, String note, String action, String verb) {
        int userId = security.currentUser().getUserId();
        expireOverdue();
        String cleanNote = trimToNull(note);
        if (cleanNote != null && cleanNote.length() > 500) {
            throw new ValidationException("note", "الملاحظة يجب ألا تزيد عن 500 حرف.");
        }
        // read before locking: while the row is locked only the quotation row itself is touched (no deadlock with a
        // sale posting, which locks sales and products first and the quotation last)
        String no = quotationDao.findById(quotationId).map(Quotation::getQuotationNo)
                .orElseThrow(() -> new ValidationException(STATUS, "عرض السعر غير موجود."));
        TransactionManager.inTransaction(con -> {
            QuotationStatus status = quotationDao.lockStatus(con, quotationId)
                    .orElseThrow(() -> new ValidationException(STATUS, "عرض السعر غير موجود."));
            if (!status.canMoveTo(target) || !quotationDao.changeStatus(con, quotationId, status, target, userId, cleanNote)) {
                throw invalidTransition(status, target);
            }
            auditLogDao.log(con, userId, action, TABLE, String.valueOf(quotationId), verb + " عرض السعر " + no
                    + " (" + status.getLabelAr() + " → " + target.getLabelAr() + ")" + (cleanNote == null ? "" : ": " + cleanNote));
            return null;
        });
        return quotationDao.findById(quotationId).orElseThrow();
    }

    static ValidationException invalidTransition(QuotationStatus from, QuotationStatus to) {
        String why = switch (from) {
            case EXPIRED -> "انتهت صلاحية العرض.";
            case CONVERTED -> "تم تحويل العرض إلى فاتورة؛ لا يمكن تغييره.";
            case REJECTED -> "العرض مرفوض.";
            default -> "الانتقال غير مسموح.";
        };
        return new ValidationException(STATUS, "لا يمكن نقل عرض السعر من \"" + from.getLabelAr() + "\" إلى \""
                + to.getLabelAr() + "\": " + why);
    }

    @Override
    public void deleteDraft(int quotationId) {
        security.requirePermission(Permission.QUOTATIONS_EDIT);
        int userId = security.currentUser().getUserId();
        Quotation q = quotationDao.findById(quotationId)
                .orElseThrow(() -> new ValidationException(STATUS, "عرض السعر غير موجود."));
        TransactionManager.inTransaction(con -> {
            if (!quotationDao.deleteDraft(con, quotationId)) {
                throw new ValidationException(STATUS, "لا يُحذف إلا عرض السعر في حالة \"مسودة\".");
            }
            auditLogDao.log(con, userId, AuditLogDao.QUOTATION_DELETED, TABLE, String.valueOf(quotationId),
                    "حذف مسودة عرض السعر " + q.getQuotationNo());
            return null;
        });
    }

    // ======================= Conversion to a sale =======================

    @Override
    public QuotationConversion convert(int quotationId, UUID requestId) {
        security.requirePermission(Permission.QUOTATIONS_CONVERT);
        security.requirePermission(Permission.SALES_CREATE);
        int userId = security.currentUser().getUserId();
        expireOverdue();

        // 1) one live sale per quotation: converting again returns it (also guarded by UX_Sales_quotation)
        Optional<QuotationConversion> existing = existingConversion(quotationId);
        if (existing.isPresent()) {
            return existing.get();
        }

        // 2) the quotation must be ACCEPTED and still valid (row locked while checking; only that row is read, so a
        //    concurrent posting of its sale — sales, products, then the quotation — cannot deadlock with it)
        TransactionManager.inTransaction(con -> {
            QuotationStatus status = quotationDao.lockStatus(con, quotationId)
                    .orElseThrow(() -> new ValidationException(STATUS, "عرض السعر غير موجود."));
            if (status != QuotationStatus.ACCEPTED) {
                throw new ValidationException(STATUS, switch (status) {
                    case EXPIRED -> "انتهت صلاحية عرض السعر؛ لا يمكن تحويله. أنشئ عرضًا جديدًا.";
                    case CONVERTED -> "تم تحويل عرض السعر إلى فاتورة مسبقًا.";
                    case REJECTED -> "عرض السعر مرفوض؛ لا يمكن تحويله.";
                    default -> "يجب قبول عرض السعر أولًا قبل تحويله إلى فاتورة.";
                });
            }
            if (quotationDao.isPastValidity(con, quotationId)) {
                throw new ValidationException(STATUS, "انتهت صلاحية عرض السعر؛ لا يمكن تحويله. أنشئ عرضًا جديدًا.");
            }
            return null;
        });
        Quotation q = quotationDao.findById(quotationId).orElseThrow();

        // 3) the sale draft, through the normal sales service (customer, products, prices, discounts re-validated)
        Sale sale = new Sale();
        sale.setCustomerId(q.getCustomerId());
        sale.setSaleType(q.getPriceType());
        sale.setDiscountAmount(q.getDiscountAmount());
        List<SaleItem> items = new ArrayList<>();
        for (QuotationItem i : q.getItems()) {
            items.add(i.toSaleItem());
        }
        sale.setItems(items);
        sale.setNotes("من عرض السعر " + q.getQuotationNo() + (q.getDisplayName() != null && q.isWalkIn()
                ? " — " + q.getDisplayName() : ""));
        sale.setRequestId(requestId);
        sale.setQuotationId(quotationId);
        Sale draft;
        try {
            draft = saleService.saveDraft(sale, PaymentType.CASH);
        } catch (DataAccessException e) {
            if (e.violates("UX_Sales_quotation")) {
                return existingConversion(quotationId).orElseThrow(() -> e);   // a concurrent conversion won
            }
            throw e;
        }
        if (!Integer.valueOf(quotationId).equals(draft.getQuotationId())) {
            // the request id belonged to another sale: never link a foreign sale
            throw new ValidationException(STATUS, "تعذّر التحويل: رقم الطلب مستخدم لفاتورة أخرى؛ أعد المحاولة.");
        }

        // 4) remember the link and audit (the quotation stays ACCEPTED until the sale is posted)
        TransactionManager.inTransaction(con -> {
            quotationDao.linkSale(con, quotationId, draft.getSaleId());
            auditLogDao.log(con, userId, AuditLogDao.QUOTATION_CONVERSION_STARTED, TABLE, String.valueOf(quotationId),
                    "بدء تحويل عرض السعر " + q.getQuotationNo() + " إلى مسودة الفاتورة " + draft.getSaleNo());
            return null;
        });
        return new QuotationConversion(draft.getSaleId(), draft.getSaleNo(), warnings(q), false);
    }

    private Optional<QuotationConversion> existingConversion(int quotationId) {
        return quotationDao.liveSaleId(quotationId).flatMap(saleDao::findById)
                .map(s -> new QuotationConversion(s.getSaleId(), s.getSaleNo(),
                        List.of("لعرض السعر فاتورة " + s.getStatus().getLabelAr() + " بالفعل: " + s.getSaleNo()
                                + "؛ لم تُنشأ فاتورة ثانية."), true));
    }

    /** What changed since the quotation: current list prices and stock. Nothing is replaced silently. */
    private List<String> warnings(Quotation q) {
        List<String> out = new ArrayList<>();
        for (QuotationItem i : q.getItems()) {
            Optional<Product> p = productDao.findById(i.getProductId());
            if (p.isEmpty()) {
                continue;
            }
            BigDecimal list = MoneyUtil.of(q.getPriceType().priceOf(p.get()));
            if (list.compareTo(i.getUnitPrice()) != 0) {
                out.add("سعر \"" + i.getProductName() + "\" الحالي " + MoneyUtil.format(list) + " يختلف عن سعر العرض "
                        + MoneyUtil.format(i.getUnitPrice()) + "؛ تم الإبقاء على سعر العرض المتفق عليه.");
            }
            if (i.getQuantity().compareTo(p.get().getQuantity()) > 0) {
                out.add("المتوفر حاليًا من \"" + i.getProductName() + "\" " + QuantityUtil.format(p.get().getQuantity())
                        + " أقل من كمية العرض " + QuantityUtil.format(i.getQuantity())
                        + "؛ لن تُعتمد الفاتورة حتى تعديل الكمية.");
            }
        }
        return out;
    }
}
