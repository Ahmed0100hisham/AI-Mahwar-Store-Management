package com.almahwar.service;

import com.almahwar.model.PaymentMethod;
import com.almahwar.model.RefundPlan;
import com.almahwar.model.ReturnDocument;
import com.almahwar.model.ReturnFilter;
import com.almahwar.model.ReturnKind;
import com.almahwar.model.ReturnLine;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Sales returns (مرتجع مبيعات, SRN-…) and purchase returns (مرتجع مشتريات, PRN-…), always against a POSTED original
 * document. A return is posted at once in <b>one</b> transaction and is never edited or deleted.
 * <p>
 * Sales return: lock the sale, check what is still returnable per line, lock the customer (not the walk-in one) and
 * the products (product_id ASC), stock back in (SALE_RETURN at the sale line's historical cost), customer ledger
 * SALE_RETURN for the value, money paid back only for the part the customer no longer owes, audit.
 * <p>
 * Purchase return: lock the purchase, check what is still returnable, lock the products (product_id ASC) and check
 * the stock is there, lock the supplier, stock out (PURCHASE_RETURN), supplier ledger PURCHASE_RETURN, money received
 * back only for the part we no longer owe, audit.
 * <p>
 * Permissions: {@code SALE_RETURNS} / {@code PURCHASE_RETURNS}.
 */
public interface ReturnService {

    // Field names used in ValidationException.getErrors()
    String ORIGINAL = "originalId";
    String LINES = "lines";
    String REASON = "reason";
    String NOTES = "notes";
    String METHOD = "refundMethod";

    int MAX_LIST_ROWS = 500;

    /** Methods money can be paid / received back with. */
    List<PaymentMethod> METHODS = FinanceRules.METHODS;

    List<ReturnDocument> search(ReturnKind kind, ReturnFilter filter);

    /** With its lines. */
    Optional<ReturnDocument> findById(ReturnKind kind, int returnId);

    /**
     * The lines of a posted sale / purchase with the original, already returned and remaining quantities and the
     * net unit value a return gets.
     */
    List<ReturnLine> returnableLines(ReturnKind kind, int originalId);

    /** How a return of {@code value} would be settled now (account credit vs money back); a preview only. */
    RefundPlan plan(ReturnKind kind, int originalId, BigDecimal value);

    /**
     * Posts a return: {@code kind}, {@code originalId}, lines (original item id + quantity), reason, optional notes,
     * the method money would move with, and a request id. A request id that was already saved returns that return.
     *
     * @return the saved return with {@code balanceAfter}
     * @throws ValidationException with Arabic messages (e.g. more than the remaining quantity, not enough stock)
     */
    ReturnDocument create(ReturnDocument request);

    String suggestNumber(ReturnKind kind);
}
