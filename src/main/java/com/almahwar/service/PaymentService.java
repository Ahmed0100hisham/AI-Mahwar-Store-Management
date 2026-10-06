package com.almahwar.service;

import com.almahwar.model.PartyPayment;
import com.almahwar.model.PartyType;
import com.almahwar.model.PaymentMethod;

import java.math.BigDecimal;
import java.util.List;

/**
 * Customer collections (سند قبض, {@code Customer_Payments}) and supplier payments (سند صرف, {@code Supplier_Payments}).
 * <p>
 * Recording a payment runs in <b>one</b> transaction: the party's row is locked, the amount is checked against what
 * is owed (no overpayment), the payment is inserted, {@link AccountLedger} posts a PAYMENT entry (which is the only
 * thing that changes the party's balance), {@code Cash_Transactions} gets the money IN (customer) or OUT (supplier),
 * and the audit log is written. If anything fails, nothing is saved. A payment never touches stock.
 * <p>
 * Permissions: {@code CUSTOMER_PAYMENTS} / {@code SUPPLIER_PAYMENTS}.
 */
public interface PaymentService {

    // Field names used in ValidationException.getErrors()
    String PARTY = "partyId";
    String AMOUNT = "amount";
    String METHOD = "paymentMethod";
    String REFERENCE = "referenceNo";
    String NOTES = "notes";
    String DATE = "paymentDay";

    /** Methods offered on screen. */
    List<PaymentMethod> METHODS = FinanceRules.METHODS;

    /**
     * Records a customer or supplier payment ({@code payment.getPartyType()}). A payment whose request id was
     * already saved returns the saved payment instead of paying twice.
     *
     * @return the saved payment, with {@code balanceAfter}
     * @throws ValidationException with Arabic messages (e.g. amount above what is owed)
     */
    PartyPayment record(PartyPayment payment);

    /** The party's payments, newest first. */
    List<PartyPayment> history(PartyType party, int partyId);

    /** What the customer owes us / what we owe the supplier now (0 when nothing). */
    BigDecimal outstanding(PartyType party, int partyId);

    /** Customers who owe us / suppliers we owe, with the amount (the payment screen's list). */
    List<com.almahwar.model.OutstandingParty> outstandingParties(PartyType party);

    /** The number the next payment will most likely get. */
    String suggestNumber(PartyType party);
}
