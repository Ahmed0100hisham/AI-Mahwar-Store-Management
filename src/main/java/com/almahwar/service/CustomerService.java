package com.almahwar.service;

import com.almahwar.model.AccountStatement;
import com.almahwar.model.CreditStatus;
import com.almahwar.model.Customer;
import com.almahwar.model.PartyFilter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Customer management and customer accounts. Meant to be reused unchanged by POS, sales,
 * payments, returns, quotations and the future REST API.
 * <p>
 * Balances, credit limits and opening balances are {@code null} for users without
 * {@code CUSTOMER_BALANCE_VIEW}. Customers are never deleted, only deactivated.
 */
public interface CustomerService {

    // Field names used in ValidationException.getErrors()
    String CODE = "customerCode";
    String NAME = "name";
    String TYPE = "customerType";
    String PHONE = "phone";
    String PHONE2 = "phone2";
    String EMAIL = "email";
    String AREA = "area";
    String ADDRESS = "address";
    String CREDIT_LIMIT = "creditLimit";
    String OPENING_BALANCE = "openingBalance";
    String NOTES = "notes";

    List<Customer> search(PartyFilter filter);

    Optional<Customer> findById(int customerId);

    /** The code a new customer gets when the code field is left empty, e.g. {@code C-0007}. */
    String suggestCode();

    /**
     * Adds a customer and, when {@code openingBalance} is not zero, its {@code OPENING_BALANCE}
     * ledger entry, in one transaction. A blank code is generated automatically.
     *
     * @param openingBalance positive = the customer owes us; negative = we owe the customer
     */
    Customer create(Customer customer, BigDecimal openingBalance);

    /** Saves contact data, type, credit limit and status; never the balance or opening balance. */
    Customer update(Customer customer);

    void setActive(int customerId, boolean active);

    /** Other customers already using this phone number (as phone or alternate phone); a warning, not an error. */
    List<Customer> findSamePhone(String phone, Integer excludeCustomerId);

    // ---------- Accounts ----------

    CreditStatus creditStatus(int customerId);

    /**
     * For POS / sales: may the customer take {@code newCreditAmount} more on credit?
     * Needs only {@code CUSTOMERS_VIEW}, so a cashier gets a yes/no and a message, not the figures.
     */
    CreditDecision checkCredit(int customerId, BigDecimal newCreditAmount);

    /**
     * For any future transaction (sale, payment, quotation ...): the customer, if active.
     *
     * @throws ValidationException if the customer does not exist or is inactive
     */
    Customer requireActive(int customerId);

    AccountStatement statement(int customerId, LocalDate from, LocalDate to, String search);

    /** Customers whose cached balance differs from the ledger (should always be empty). */
    List<Integer> balanceMismatches();

    /** Result of a credit check, with an Arabic explanation when refused. */
    record CreditDecision(boolean allowed, String message) {
    }
}
