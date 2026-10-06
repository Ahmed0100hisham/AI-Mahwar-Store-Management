package com.almahwar.model;

import com.almahwar.model.ProductFilter.ActiveStatus;

/**
 * Search criteria for the customer and supplier lists.
 *
 * @param text           matched against code, name and phone numbers
 * @param withBalance    only parties with a balance other than zero
 * @param overCreditOnly customers only: balance above the credit limit
 */
public record PartyFilter(String text, ActiveStatus status, boolean withBalance, boolean overCreditOnly) {

    public PartyFilter {
        status = status == null ? ActiveStatus.ALL : status;
        text = text == null || text.isBlank() ? null : text.trim();
    }

    public static PartyFilter all() {
        return new PartyFilter(null, ActiveStatus.ALL, false, false);
    }

    public static PartyFilter search(String text) {
        return new PartyFilter(text, ActiveStatus.ALL, false, false);
    }
}
