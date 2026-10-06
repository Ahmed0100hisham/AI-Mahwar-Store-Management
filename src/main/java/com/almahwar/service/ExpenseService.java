package com.almahwar.service;

import com.almahwar.model.Expense;
import com.almahwar.model.ExpenseFilter;
import com.almahwar.model.PaymentMethod;

import java.math.BigDecimal;
import java.util.List;

/**
 * Expenses (المصروفات). Recording one runs in one transaction: the {@code Expenses} row, its
 * {@code Cash_Transactions} OUT row (same date) and the audit log. Expenses never touch a customer or supplier
 * account, nor stock. Permission: {@code EXPENSES}.
 */
public interface ExpenseService {

    // Field names used in ValidationException.getErrors()
    String CATEGORY = "category";
    String DESCRIPTION = "description";
    String AMOUNT = "amount";
    String METHOD = "paymentMethod";
    String REFERENCE = "referenceNo";
    String NOTES = "notes";
    String DATE = "expenseDay";

    int MAX_LIST_ROWS = 500;

    List<PaymentMethod> METHODS = FinanceRules.METHODS;

    List<Expense> search(ExpenseFilter filter);

    /** Σ amount of every expense matching the filter. */
    BigDecimal total(ExpenseFilter filter);

    /** A request id that was already saved returns the saved expense instead of recording it twice. */
    Expense create(Expense expense);

    String suggestNumber();
}
