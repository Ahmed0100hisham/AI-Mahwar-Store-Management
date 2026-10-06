package com.almahwar.service;

import com.almahwar.dao.AuditLogDao;
import com.almahwar.dao.ProductDao;
import com.almahwar.dao.StockMovementDao;
import com.almahwar.dao.TransactionManager;
import com.almahwar.dao.UnitDao;
import com.almahwar.model.MovementFilter;
import com.almahwar.model.Permission;
import com.almahwar.model.Product;
import com.almahwar.model.ProductFilter;
import com.almahwar.model.StockAdjustment;
import com.almahwar.model.StockMovement;
import com.almahwar.model.Unit;
import com.almahwar.util.QuantityUtil;

import java.util.List;

import static com.almahwar.service.Validation.trimToNull;

/** Inventory on SQL Server: stock list, manual adjustments and movement history. */
public class InventoryServiceImpl implements InventoryService {

    private static final List<String> REASONS = List.of("جرد المخزن", "تالف", "فرق كمية", "تصحيح إدخال");

    private final ProductDao productDao;
    private final UnitDao unitDao;
    private final StockMovementDao movementDao;
    private final StockLedger ledger;
    private final AuditLogDao auditLogDao;
    private final SecurityContext security;

    public InventoryServiceImpl(ProductDao productDao, UnitDao unitDao, StockMovementDao movementDao, StockLedger ledger,
                                AuditLogDao auditLogDao, SecurityContext security) {
        this.productDao = productDao;
        this.unitDao = unitDao;
        this.movementDao = movementDao;
        this.ledger = ledger;
        this.auditLogDao = auditLogDao;
        this.security = security;
    }

    @Override
    public List<Product> stockList(ProductFilter filter) {
        security.requireAnyPermission(Permission.INVENTORY, Permission.INVENTORY_ADJUST);
        List<Product> products = productDao.search(filter == null ? ProductFilter.all() : filter);
        if (!security.hasPermission(Permission.PRODUCT_COST)) {
            products.forEach(p -> p.setPurchasePrice(null));
        }
        return products;
    }

    @Override
    public StockMovement adjust(StockAdjustment request) {
        security.requirePermission(Permission.INVENTORY_ADJUST);
        int userId = security.currentUser().getUserId();
        String reason = trimToNull(request.reason());

        Validation v = validate(request, reason);
        v.throwIfAny();
        Product product = productDao.findById(request.productId())
                .orElseThrow(() -> new ValidationException(PRODUCT, "المنتج غير موجود."));
        if (!product.isActive()) {
            v.error(PRODUCT, "المنتج \"" + product.getNameAr() + "\" معطّل؛ فعّله أولًا ثم أجرِ التسوية.");
        }
        Unit unit = unitDao.findById(product.getUnitId()).orElseThrow();
        v.wholeNumberUnless(unit.isAllowsDecimal(), QUANTITY, request.quantity(), unit.getNameAr());
        v.throwIfAny();

        return TransactionManager.inTransaction(con -> {
            StockMovement m = ledger.post(con, product.getProductId(), request.type(), request.quantity(),
                    null, null, reason, userId);
            auditLogDao.log(con, userId, AuditLogDao.STOCK_ADJUSTMENT, "Stock_Movements",
                    String.valueOf(m.getMovementId()),
                    request.type().getLabelAr() + " " + product.getProductCode() + ": "
                            + QuantityUtil.format(m.getQuantityBefore()) + " → "
                            + QuantityUtil.format(m.getQuantityAfter()) + " (" + reason + ")");
            m.setProductCode(product.getProductCode());
            m.setProductName(product.getNameAr());
            m.setUnitName(product.getUnitName());
            m.setUserName(security.currentUser().getFullName());
            return m;
        });
    }

    /** Checks that need no database; package-private for unit tests. */
    static Validation validate(StockAdjustment request, String reason) {
        Validation v = new Validation();
        if (request.productId() == null) {
            v.error(PRODUCT, "اختر المنتج.");
        }
        if (request.type() == null) {
            v.error(TYPE, "اختر نوع التسوية.");
        } else if (!request.type().isManualAdjustment()) {
            v.error(TYPE, "نوع الحركة \"" + request.type().getLabelAr() + "\" لا يُدخل يدويًا.");
        }
        v.positive(QUANTITY, request.quantity(), "الكمية");
        v.required(REASON, reason, "سبب التسوية مطلوب.");
        v.maxLength(REASON, reason, 250, "سبب التسوية");
        return v;
    }

    @Override
    public List<StockMovement> history(MovementFilter filter) {
        security.requireAnyPermission(Permission.INVENTORY, Permission.INVENTORY_ADJUST);
        MovementFilter f = filter == null ? MovementFilter.all() : filter;
        if (f.from() != null && f.to() != null && f.from().isAfter(f.to())) {
            throw new ValidationException("from", "تاريخ البداية يجب أن يكون قبل تاريخ النهاية.");
        }
        return movementDao.find(f, MAX_HISTORY_ROWS);
    }

    @Override
    public List<String> suggestedReasons() {
        return REASONS;
    }

    @Override
    public long countLowStock() {
        security.requireAnyPermission(Permission.INVENTORY, Permission.INVENTORY_ADJUST);
        return productDao.countLowStock();
    }
}
