package com.almahwar.service;

import com.almahwar.model.AccountStatement;
import com.almahwar.model.PartyFilter;
import com.almahwar.model.Supplier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Supplier management and supplier accounts, reusable by purchases, payments, returns and the
 * future REST API. Balances are {@code null} for users without {@code SUPPLIER_BALANCE_VIEW}.
 * Suppliers are never deleted, only deactivated.
 */
public interface SupplierService {

    String CODE = "supplierCode";
    String NAME = "name";
    String CONTACT_PERSON = "contactPerson";
    String PHONE = "phone";
    String PHONE2 = "phone2";
    String EMAIL = "email";
    String COUNTRY = "country";
    String AREA = "area";
    String ADDRESS = "address";
    String OPENING_BALANCE = "openingBalance";
    String NOTES = "notes";

    List<Supplier> search(PartyFilter filter);

    Optional<Supplier> findById(int supplierId);

    String suggestCode();

    /**
     * Adds a supplier and, when {@code openingBalance} is not zero, its {@code OPENING_BALANCE}
     * ledger entry, in one transaction.
     *
     * @param openingBalance positive = we owe the supplier; negative = the supplier owes us
     */
    Supplier create(Supplier supplier, BigDecimal openingBalance);

    /** Saves contact data and status; never the balance or opening balance. */
    Supplier update(Supplier supplier);

    void setActive(int supplierId, boolean active);

    List<Supplier> findSamePhone(String phone, Integer excludeSupplierId);

    /**
     * For any future transaction (purchase, payment ...): the supplier, if active.
     *
     * @throws ValidationException if the supplier does not exist or is inactive
     */
    Supplier requireActive(int supplierId);

    AccountStatement statement(int supplierId, LocalDate from, LocalDate to, String search);

    List<Integer> balanceMismatches();
}
