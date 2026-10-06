package com.almahwar.service;

import com.almahwar.model.Customer;
import com.almahwar.model.PaymentType;
import com.almahwar.model.Product;
import com.almahwar.model.Sale;
import com.almahwar.model.SaleFilter;
import com.almahwar.model.User;

import java.util.List;
import java.util.Optional;

/**
 * Sales invoices and the point of sale (نقطة البيع والمبيعات).
 * <p>
 * A sale is a DRAFT (a held cart, no effect anywhere) until it is POSTED. Posting runs in <b>one</b> database
 * transaction: the customer row and the products are locked; the customer, the credit limit, the
 * products, prices, discounts and the stock are validated again; each line's historical unit cost is fixed;
 * stock goes out (Stock_Movements SALE); the customer ledger gets SALE and PAYMENT; the paid amount enters
 * Cash_Transactions; the audit log is written; the sale becomes POSTED. If anything fails, nothing is saved.
 * Posted sales are never edited or deleted.
 * <p>
 * Unit costs, cost totals and profit are {@code null} for users without {@code SALES_COST_VIEW} /
 * {@code SALES_PROFIT_VIEW}.
 */
public interface SaleService {

    // Field names used in ValidationException.getErrors()
    String CUSTOMER = "customerId";
    String ITEMS = "items";
    String DISCOUNT = "discountAmount";
    String NOTES = "notes";
    String SALE_TYPE = "saleType";
    String PAYMENT_TYPE = PaymentRules.TYPE;
    String PAID = PaymentRules.PAID;
    String PAYMENT_METHOD = PaymentRules.METHOD;

    int MAX_LIST_ROWS = 500;

    List<Sale> search(SaleFilter filter);

    /** With its items. */
    Optional<Sale> findById(int saleId);

    /** The number the next sale will most likely get (shown on the POS). */
    String suggestNumber();

    /** The walk-in customer ("عميل نقدي") the POS starts with. */
    Customer walkInCustomer();

    /** Active products matching a name (Arabic/English), code or barcode; costs hidden as for the products list. */
    List<Product> searchProducts(String text);

    /** The product whose barcode or code is exactly {@code code} (a scanner read), active or not. */
    Optional<Product> findByScan(String code);

    /** Active customers matching a code, name or phone. */
    List<Customer> searchCustomers(String text);

    /** Users who have sales, for the cashier filter. */
    List<User> cashiers();

    /**
     * Creates or updates a DRAFT (a held sale). {@code sale.getPaymentMethod()} / {@code getPaidAmount()} are
     * the method and amount of the paid part and are used only for {@link PaymentType#PARTIAL}.
     * A new draft with a {@code requestId} that was already saved returns the saved sale.
     */
    Sale saveDraft(Sale sale, PaymentType paymentType);

    /**
     * Posts an existing DRAFT (see class notes). Posting twice is refused.
     *
     * @param overrideCreditLimit the user confirmed selling beyond the credit limit (needs
     *                            {@code CUSTOMER_CREDIT_OVERRIDE}; logged in the audit log)
     * @throws InsufficientStockException  when a product is short at posting time
     * @throws CreditLimitExceededException when the credit limit would be exceeded
     */
    Sale post(int saleId, boolean overrideCreditLimit);

    /** Creates and posts a new sale in one transaction (the POS "complete sale"). Same rules as {@link #post}. */
    Sale saveAndPost(Sale sale, PaymentType paymentType, boolean overrideCreditLimit);

    /** DRAFT → CANCELLED (kept, never deleted). Posted sales are corrected by returns, later. */
    void cancelDraft(int saleId);
}
