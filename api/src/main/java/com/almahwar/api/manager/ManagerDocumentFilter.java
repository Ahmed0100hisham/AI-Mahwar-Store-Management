package com.almahwar.api.manager;

import com.almahwar.api.error.FieldValidationException;
import com.almahwar.model.PaymentMethod;
import com.almahwar.model.SaleStatus;
import com.almahwar.model.QuotationStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/** Enum codes follow the released core exactly; values are always bound parameters. */
public record ManagerDocumentFilter(Integer customerId,
        @Schema(description="Invoice: DRAFT/POSTED/CANCELLED. Quotation: DRAFT/SENT/ACCEPTED/REJECTED/EXPIRED/CONVERTED") String status,
        @Schema(description="Invoice only: released PaymentMethod code") String paymentMethod,
        @Schema(description="Quotation only: valid_until before SQL business today; independent of stored status") Boolean pastValidity) {
    public void validate(boolean invoice) {
        if(customerId!=null && customerId<1) throw invalid("customerId");
        try {
            if(status!=null) { if(invoice) SaleStatus.valueOf(status); else QuotationStatus.valueOf(status); }
            if(paymentMethod!=null) { if(!invoice) throw invalid("paymentMethod"); PaymentMethod.valueOf(paymentMethod); }
            if(invoice && pastValidity!=null) throw invalid("pastValidity");
        } catch(IllegalArgumentException e) { throw invalid("filter"); }
    }
    private static FieldValidationException invalid(String field) { return new FieldValidationException(field,"Invalid document filter."); }
}
