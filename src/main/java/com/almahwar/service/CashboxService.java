package com.almahwar.service;

import com.almahwar.model.CashFilter;
import com.almahwar.model.CashMovement;
import com.almahwar.model.CashSummary;
import com.almahwar.model.PaymentMethod;
import com.almahwar.model.User;

import java.util.List;

/**
 * The cashbox / treasury (الخزنة): every money movement of the shop is a {@code Cash_Transactions} row written by
 * the document that caused it (sales, purchases, payments, expenses) or by a manual deposit / withdrawal here.
 * The balance is always Σ IN − Σ OUT; it is never stored or edited.
 * <p>
 * Permissions: {@code CASH} to view, {@code CASH_ADJUST} for manual deposits and withdrawals.
 */
public interface CashboxService {

    // Field names used in ValidationException.getErrors()
    String AMOUNT = "amount";
    String METHOD = "paymentMethod";
    String REASON = "description";
    String REFERENCE = "referenceNo";
    String NOTES = "notes";

    int MAX_LIST_ROWS = 1000;

    List<PaymentMethod> METHODS = FinanceRules.METHODS;

    CashSummary summary();

    List<CashMovement> search(CashFilter filter);

    /** Users who recorded movements (for the user filter). */
    List<User> users();

    /** Money put into the treasury by hand (amount, method, reason, reference, notes, request id). */
    CashMovement deposit(CashMovement deposit);

    /** Money taken out by hand; refused when it is more than the current balance. */
    CashMovement withdraw(CashMovement withdrawal);
}
