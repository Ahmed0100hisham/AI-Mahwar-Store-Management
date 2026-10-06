package com.almahwar.model;

import java.util.List;

/**
 * Result of converting an accepted quotation: the sale <b>draft</b> to review and complete in the point of sale.
 *
 * @param saleId      the draft (or the sale already made from this quotation)
 * @param saleNo      its number
 * @param warnings    what changed since the quotation (current price, stock) — nothing was changed silently
 * @param alreadyMade {@code true} when the quotation already had a live sale (no second one was created)
 */
public record QuotationConversion(int saleId, String saleNo, List<String> warnings, boolean alreadyMade) {
}
