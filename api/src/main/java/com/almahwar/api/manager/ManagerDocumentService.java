package com.almahwar.api.manager;

import com.almahwar.api.core.SpringSecurityContext;
import com.almahwar.api.error.FieldValidationException;
import com.almahwar.api.web.PageResponse;
import com.almahwar.api.web.PageQuery;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import static com.almahwar.api.manager.ManagerDocumentResponses.*;
import static com.almahwar.model.Permission.*;

/** Repeats controller authorization, before validation or persistence, for direct calls as well. */
@Service
public class ManagerDocumentService {
    private final ManagerAccess access;
    private final SpringSecurityContext security;
    private final ManagerDocumentRepository repository;
    private final ManagerAnalyticsRepository analytics;
    private final ManagerQueryService reports;
    public ManagerDocumentService(ManagerAccess access,SpringSecurityContext security,ManagerDocumentRepository repository,
            ManagerAnalyticsRepository analytics,ManagerQueryService reports) {
        this.access=access;this.security=security;this.repository=repository;this.analytics=analytics;this.reports=reports;
    }
    public PageResponse<Invoice> invoices(ManagerDateRange.Input range,ManagerPage page,ManagerDocumentFilter filter) {
        access.require("invoices");filter.validate(true);page.validate("date","number","total");
        return repository.invoices(ManagerDateRange.resolve(range,366,analytics::today),page,filter,
                security.hasPermission(SALES_COST_VIEW),security.hasPermission(SALES_PROFIT_VIEW));
    }
    public InvoiceDetail invoice(int id,ManagerPage items,Integer returnsPage,Integer returnsSize) {
        access.require("invoices");positive(id);var paging=detailPage(items);
        var returns=new ManagerPage(returnsPage,returnsSize,null,null).paging();
        return repository.invoice(id,paging,returns,security.hasPermission(SALES_COST_VIEW),security.hasPermission(SALES_PROFIT_VIEW));
    }
    public PageResponse<Quotation> quotations(ManagerDateRange.Input range,ManagerPage page,ManagerDocumentFilter filter) {
        access.require("quotations");filter.validate(false);page.validate("date","number","total","validUntil");
        var resolved=ManagerDateRange.resolve(range,366,analytics::today);
        return repository.quotations(resolved,page,filter,analytics.today(),access.allowed("invoices"));
    }
    public QuotationDetail quotation(int id,ManagerPage items) {
        access.require("quotations");positive(id);var paging=detailPage(items);
        return repository.quotation(id,paging,analytics.today(),access.allowed("invoices"));
    }
    public Daily daily(LocalDate requested) {
        access.require("dashboard");
        LocalDate day=requested==null?analytics.today():requested;
        var input=new ManagerDateRange.Input(null,day,day);
        ManagerDateRange.resolve(input,1,analytics::today);
        return new Daily(day,access.allowed("sales")?reports.sales(input):null,
                access.allowed("expenses")?reports.expenses(input):null,
                access.allowed("cashbox")?reports.cashbox(input):null,
                access.allowed("inventory")?new InventorySnapshot(analytics.today(),reports.inventory()):null);
    }
    private static PageQuery detailPage(ManagerPage page) {
        if(page.search()!=null || page.sort()!=null) throw new FieldValidationException("sort","Detail collections use fixed item ID order and no search.");
        return page.paging();
    }
    private static void positive(int id) { if(id<1) throw new FieldValidationException("id","Use a positive identifier."); }
}
