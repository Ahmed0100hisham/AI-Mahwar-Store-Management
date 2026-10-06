package com.almahwar.model;

/**
 * The company profile (بيانات الشركة) printed on invoices, quotations and reports. Optional fields are
 * {@code null} when empty. The currency is shown here but is not editable: amounts are KWD with 3 decimals.
 */
public record CompanySettings(String nameAr, String nameEn, String phone, String phone2, String email,
                              String address, String country, String currencyCode, String taxNumber,
                              String crNumber) {

    /** Copy with another currency code (the service always sets the fixed one). */
    public CompanySettings withCurrency(String code) {
        return new CompanySettings(nameAr, nameEn, phone, phone2, email, address, country, code, taxNumber, crNumber);
    }
}
