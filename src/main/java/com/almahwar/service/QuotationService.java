package com.almahwar.service;

import com.almahwar.model.Customer;
import com.almahwar.model.Product;
import com.almahwar.model.Quotation;
import com.almahwar.model.QuotationConversion;
import com.almahwar.model.QuotationFilter;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Quotations (عروض الأسعار, QUO-…). A quotation is <b>financially neutral</b>: no operation here writes stock
 * movements, ledger entries, cash movements or sales figures.
 * <p>
 * Workflow (see {@link com.almahwar.model.QuotationStatus}): DRAFT → SENT → ACCEPTED / REJECTED; DRAFT may be accepted
 * directly; SENT may be reopened as a DRAFT; a passed validity date makes DRAFT / SENT / ACCEPTED quotations EXPIRED.
 * <p>
 * Conversion: an ACCEPTED, still valid quotation becomes a sale <b>draft</b> through the existing {@link SaleService}
 * (same validation, same rules); the quotation is CONVERTED only when that sale is posted, inside the sale's posting
 * transaction. One quotation can have only one live sale (database constraint).
 */
public interface QuotationService {

    // Field names used in ValidationException.getErrors()
    String CUSTOMER = "customerId";
    String ITEMS = "items";
    String DISCOUNT = "discountAmount";
    String VALID_UNTIL = "validUntil";
    String NOTES = "notes";
    String TERMS = "terms";
    String PROSPECT_NAME = "prospectName";
    String PROSPECT_PHONE = "prospectPhone";
    String STATUS = "status";

    int MAX_LIST_ROWS = 500;

    List<Quotation> search(QuotationFilter filter);

    /** With its items (and the products' current stock, for information). */
    Optional<Quotation> findById(int quotationId);

    String suggestNumber();

    /** Active customers to quote for (the walk-in customer included); balances only with their permission. */
    List<Customer> activeCustomers();

    /** Active products by name, code or barcode (no cost); for the quotation form. */
    List<Product> searchProducts(String text);

    /**
     * Creates a DRAFT (no id) or saves an existing DRAFT. A new quotation whose request id was already saved returns
     * that quotation instead of creating a second one.
     */
    Quotation save(Quotation quotation);

    /** DRAFT → SENT. */
    Quotation send(int quotationId);

    /** DRAFT / SENT → ACCEPTED, with an optional note. */
    Quotation accept(int quotationId, String note);

    /** SENT → REJECTED, with an optional note. */
    Quotation reject(int quotationId, String note);

    /** SENT → DRAFT, to change it before sending it again. */
    Quotation reopen(int quotationId);

    /** Deletes a DRAFT (never a quotation that was sent, decided or converted). */
    void deleteDraft(int quotationId);

    /**
     * Turns an ACCEPTED, still valid quotation into a sale draft (reviewed and posted in the point of sale). Converting
     * again returns the same live sale instead of making a second one.
     *
     * @param requestId one id per click (a retried request returns the same sale)
     */
    QuotationConversion convert(int quotationId, UUID requestId);
}
