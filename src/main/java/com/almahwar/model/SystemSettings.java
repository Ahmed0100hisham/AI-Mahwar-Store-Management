package com.almahwar.model;

/**
 * System settings (إعدادات النظام): defaults for new quotations and the printed footers. Changing them never
 * changes documents already saved (a quotation keeps the terms it was saved with).
 *
 * @param quotationValidityDays a new quotation is valid until its date + this many days (1–365)
 * @param quotationTerms        default terms of a new quotation, or {@code null}
 * @param invoiceFooter         printed at the bottom of sales invoices, or {@code null}
 * @param reportFooter          printed at the bottom of report pages, or {@code null}
 */
public record SystemSettings(int quotationValidityDays, String quotationTerms, String invoiceFooter,
                             String reportFooter) {
}
