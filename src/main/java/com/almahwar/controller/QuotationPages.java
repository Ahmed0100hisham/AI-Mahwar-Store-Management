package com.almahwar.controller;

import com.almahwar.config.AppContext;
import com.almahwar.controller.support.ViewLoader;
import com.almahwar.model.Permission;
import javafx.scene.Parent;

import java.util.function.Consumer;

/**
 * Where the quotation pages (details, form, print preview, and the sale made from a quotation) are shown and where
 * "back" goes. Hosted by the quotations module and by the customer details page ("عروض الأسعار" tab).
 */
final class QuotationPages {

    private final Consumer<Parent> show;
    private final Consumer<String> close;

    /**
     * @param show  puts a page in place of the host's current page
     * @param close leaves the quotation pages (back to the list or the customer), with an optional message
     */
    QuotationPages(Consumer<Parent> show, Consumer<String> close) {
        this.show = show;
        this.close = close;
    }

    void closeQuotation(String message) {
        close.accept(message);
    }

    void showDetails(int quotationId, String message) {
        show.accept(ViewLoader.<QuotationDetailsController>load("quotation-details.fxml",
                c -> c.open(this, quotationId, message)));
    }

    /**
     * @param quotationId {@code null} for a new quotation, else a DRAFT to edit
     * @param customerId  the customer to preselect for a new quotation, or {@code null}
     */
    void showForm(Integer quotationId, Integer customerId) {
        show.accept(ViewLoader.<QuotationFormController>load("quotation-form.fxml",
                c -> c.open(this, quotationId, customerId)));
    }

    void showPrint(int quotationId) {
        show.accept(QuotationPrintPage.create(this, quotationId));
    }

    /** The point of sale with the sale draft made from the quotation; "back" returns to the quotation. */
    void showPos(int quotationId, int draftSaleId, String notice) {
        show.accept(ViewLoader.<PosController>load("pos.fxml",
                c -> c.open(salePages(quotationId), draftSaleId, notice)));
    }

    void showSale(int quotationId, int saleId) {
        salePages(quotationId).showSaleDetails(saleId, null);
    }

    /** The sale pages opened from a quotation: "back" returns to that quotation. */
    private SalePages salePages(int quotationId) {
        return new SalePages() {
            @Override
            public void closeSale(String message) {
                showDetails(quotationId, message);
            }

            @Override
            public void showSaleDetails(int saleId, String message) {
                SalePages self = this;
                show.accept(ViewLoader.<SaleDetailsController>load("sale-details.fxml",
                        c -> c.open(self, saleId, message)));
            }

            @Override
            public void showSalePrint(int saleId) {
                show.accept(SalePrintPage.create(this, saleId));
            }

            @Override
            public void showPos(int draftSaleId) {
                QuotationPages.this.showPos(quotationId, draftSaleId, null);
            }

            @Override
            public boolean canOpenPos() {
                return AppContext.get().security().hasPermission(Permission.SALES_CREATE);
            }
        };
    }
}
