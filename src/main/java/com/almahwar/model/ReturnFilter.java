package com.almahwar.model;

import java.time.LocalDate;

/**
 * Search criteria for the returns lists; {@code null} fields are not filtered, dates are inclusive.
 *
 * @param text      matched against the return number, the original document's number and the party's name / code
 * @param partyId   only this customer / supplier
 * @param originalId only returns of this sale / purchase
 */
public record ReturnFilter(String text, LocalDate from, LocalDate to, Integer partyId, Integer originalId) {

    public ReturnFilter {
        text = text == null || text.isBlank() ? null : text.trim();
    }

    public static ReturnFilter all() {
        return new ReturnFilter(null, null, null, null, null);
    }

    public static ReturnFilter forParty(int partyId) {
        return new ReturnFilter(null, null, null, partyId, null);
    }

    public static ReturnFilter forOriginal(int originalId) {
        return new ReturnFilter(null, null, null, null, originalId);
    }
}
