package com.almahwar.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * {@code Quotations.status} and the allowed transitions:
 * <pre>
 * DRAFT    → SENT, ACCEPTED, EXPIRED          (editable; may be deleted)
 * SENT     → ACCEPTED, REJECTED, DRAFT (reopen to edit), EXPIRED
 * ACCEPTED → CONVERTED (when its sale is posted), EXPIRED
 * REJECTED, EXPIRED, CONVERTED: final
 * </pre>
 * A quotation never moves stock, money or accounts; only its sale does, when it is posted.
 */
public enum QuotationStatus {

    DRAFT("مسودة"),
    SENT("مُرسَل"),
    ACCEPTED("مقبول"),
    REJECTED("مرفوض"),
    EXPIRED("منتهي الصلاحية"),
    CONVERTED("محوّل لفاتورة");

    private final String labelAr;

    QuotationStatus(String labelAr) {
        this.labelAr = labelAr;
    }

    public String getLabelAr() {
        return labelAr;
    }

    /** The statuses this one may move to. */
    public Set<QuotationStatus> next() {
        return switch (this) {
            case DRAFT -> EnumSet.of(SENT, ACCEPTED, EXPIRED);
            case SENT -> EnumSet.of(ACCEPTED, REJECTED, DRAFT, EXPIRED);
            case ACCEPTED -> EnumSet.of(CONVERTED, EXPIRED);
            case REJECTED, EXPIRED, CONVERTED -> EnumSet.noneOf(QuotationStatus.class);
        };
    }

    public boolean canMoveTo(QuotationStatus target) {
        return next().contains(target);
    }

    /** Only drafts change their items, prices, discounts or customer. */
    public boolean isEditable() {
        return this == DRAFT;
    }

    public boolean isFinal() {
        return next().isEmpty();
    }
}
