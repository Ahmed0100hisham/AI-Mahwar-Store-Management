package com.almahwar.service;

import com.almahwar.model.CompanySettings;
import com.almahwar.model.Settings.About;
import com.almahwar.model.Settings.LogoInfo;
import com.almahwar.model.Settings.Snapshot;
import com.almahwar.model.SystemSettings;

import java.util.Optional;

/**
 * The central company profile and system settings (stored in the database, shared by every PC).
 * <p>
 * Reading the company profile, the system settings and the logo only needs a logged-in user: every printed
 * document shows them. Opening the settings screen needs {@code SETTINGS_VIEW}; changing anything needs
 * {@code SETTINGS_EDIT}. Each save is one transaction and is audited. Settings never touch business data, and a
 * changed default (e.g. quotation terms) never changes documents already saved.
 */
public interface SettingsService {

    // Field names used in ValidationException.getErrors()
    String NAME_AR = "nameAr";
    String NAME_EN = "nameEn";
    String PHONE = "phone";
    String PHONE2 = "phone2";
    String EMAIL = "email";
    String ADDRESS = "address";
    String COUNTRY = "country";
    String TAX_NUMBER = "taxNumber";
    String CR_NUMBER = "crNumber";
    String CURRENCY = "currencyCode";
    String VALIDITY_DAYS = "quotationValidityDays";
    String QUOTATION_TERMS = "quotationTerms";
    String INVOICE_FOOTER = "invoiceFooter";
    String REPORT_FOOTER = "reportFooter";
    String LOGO = "logo";

    /** The fixed currency: amounts are KWD with 3 decimals everywhere (not a setting). */
    String CURRENCY_CODE = "KWD";
    /** The database schema this program needs (Schema_Info). */
    String REQUIRED_SCHEMA_VERSION = "1.10.0";

    int MIN_VALIDITY_DAYS = 1;
    int MAX_VALIDITY_DAYS = 365;
    int MAX_LOGO_BYTES = 1024 * 1024;
    int MIN_LOGO_SIDE = 16;
    int MAX_LOGO_SIDE = 4000;

    /** Company profile for printing (any logged-in user). */
    CompanySettings company();

    /** Quotation defaults and footers (any logged-in user). */
    SystemSettings system();

    /** The logo image (PNG / JPEG bytes), if one is stored (any logged-in user). */
    Optional<byte[]> logo();

    /** Everything the settings screen shows ({@code SETTINGS_VIEW}). */
    Snapshot load();

    /**
     * Saves the company profile and the system settings together ({@code SETTINGS_EDIT}): both are validated first,
     * then written in one transaction — either everything is saved or nothing is.
     */
    Snapshot save(CompanySettings company, SystemSettings system);

    /** Saves the company profile ({@code SETTINGS_EDIT}); the currency cannot be changed. */
    CompanySettings saveCompany(CompanySettings company);

    /** Saves the system settings ({@code SETTINGS_EDIT}). */
    SystemSettings saveSystem(SystemSettings system);

    /**
     * Replaces the logo ({@code SETTINGS_EDIT}): a PNG or JPEG image of at most 1 MB, 16–4000 pixels per side.
     *
     * @param fileName the original file name (for display only; never used as a path)
     */
    LogoInfo changeLogo(String fileName, byte[] content);

    /** Removes the logo ({@code SETTINGS_EDIT}); prints then show the company name only. */
    void removeLogo();

    /** Program, version and database information ({@code SETTINGS_VIEW}); never credentials. */
    About about();
}
