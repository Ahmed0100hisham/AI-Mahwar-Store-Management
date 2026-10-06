package com.almahwar.service;

import com.almahwar.model.PaymentType;
import com.almahwar.model.Purchase;
import com.almahwar.model.PurchaseFilter;

import java.util.List;
import java.util.Optional;

/**
 * Purchase invoices (فواتير المشتريات).
 * <p>
 * A purchase is first a DRAFT (no effect anywhere). Posting it applies, in <b>one</b> database
 * transaction: stock in for every line (Stock_Movements PURCHASE), the product's current cost
 * ({@link CostingPolicy}), the supplier ledger (PURCHASE, and PAYMENT for what was paid), the cash
 * movement for the paid amount, and the audit log. If anything fails, nothing is saved.
 * Posted invoices are never edited or deleted.
 * <p>
 * Amounts and costs are {@code null} for users without {@code PURCHASE_COST_VIEW}.
 */
public interface PurchaseService {

    // Field names used in ValidationException.getErrors()
    String SUPPLIER = "supplierId";
    String SUPPLIER_INVOICE_NO = "supplierInvoiceNo";
    String ITEMS = "items";
    String DISCOUNT = "discountAmount";
    String NOTES = "notes";
    String PAYMENT_TYPE = PaymentRules.TYPE;
    String PAID = PaymentRules.PAID;
    String PAYMENT_METHOD = PaymentRules.METHOD;

    int MAX_LIST_ROWS = 500;

    List<Purchase> search(PurchaseFilter filter);

    /** With its items. */
    Optional<Purchase> findById(int purchaseId);

    /** The number the next purchase will most likely get (shown on the new-purchase screen). */
    String suggestNumber();

    /**
     * Creates or updates a DRAFT. {@code purchase.getPaymentMethod()} / {@code getPaidAmount()} are the
     * method and amount of the paid part and are used only for {@link PaymentType#PARTIAL}.
     * A new draft with a {@code requestId} that was already saved returns the saved purchase.
     */
    Purchase saveDraft(Purchase purchase, PaymentType paymentType);

    /** Posts an existing DRAFT (see class notes). Posting twice is refused. */
    Purchase post(int purchaseId);

    /** Creates and posts a new purchase in one transaction (the "save and post" button). */
    Purchase saveAndPost(Purchase purchase, PaymentType paymentType);

    /** DRAFT → CANCELLED (kept, never deleted). Posted purchases are corrected by returns, later. */
    void cancelDraft(int purchaseId);
}
