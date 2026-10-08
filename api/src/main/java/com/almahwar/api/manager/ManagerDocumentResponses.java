package com.almahwar.api.manager;

import com.almahwar.api.web.PageResponse;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Stable document projections, never Desktop entities or free-form operational notes. */
public final class ManagerDocumentResponses {
    private ManagerDocumentResponses() { }
    public record Person(Integer id,String code,String name,String phone) { }
    public record Creator(int id,String name) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Invoice(int id,String number,LocalDateTime date,Person customer,Creator creator,
            String status,String priceType,String paymentMethod,String paymentStatus,
            String subtotal,String discount,String tax,String total,String paid,String remaining,
            String returnedAmount,String refundedAmount,String netAmount,
            @Schema(description="List returns through this inclusive business date; absent in detail, which includes all recorded returns") LocalDate returnCutoffDate,
            @Schema(description="Historical invoice cost; omitted without SALES_COST_VIEW") String historicalCost,
            @Schema(description="Stored POSTED invoice gross profit before returns; omitted for drafts/cancelled or without SALES_PROFIT_VIEW") String grossProfit) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InvoiceLine(int id,int productId,String productCode,String name,String unit,
            String quantity,String unitPrice,String discount,String total,String returnedQuantity,
            @Schema(description="Historical unit cost, SALES_COST_VIEW only") String historicalUnitCost) { }
    public record Returned(int id,String number,LocalDateTime date,String total,String refund,String refundMethod) { }
    public record InvoiceDetail(Invoice invoice,PageResponse<InvoiceLine> items,PageResponse<Returned> returns) { }
    public record SaleReference(int id,String number,String status) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Quotation(int id,String number,LocalDateTime date,LocalDate validUntil,LocalDate businessDate,
            boolean pastValidity,String status,Person customer,String prospectName,String prospectPhone,
            Creator creator,String priceType,String subtotal,String discount,String total,
            LocalDateTime sentAt,LocalDateTime decidedAt,
            @Schema(description="Actually stored sale link and status; additionally needs SALES_VIEW, not proof of posting") SaleReference linkedSale) { }
    public record QuotationLine(int id,int productId,String productCode,String name,String unit,
            String quantity,String unitPrice,String discount,String total) { }
    public record QuotationDetail(Quotation quotation,PageResponse<QuotationLine> items) { }
    public record InventorySnapshot(LocalDate businessDate,ManagerResponses.Inventory inventory) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Daily(LocalDate date,ManagerResponses.Sales sales,ManagerResponses.Expenses expenses,
            ManagerResponses.Cashbox cashbox,
            @Schema(description="Current inventory, explicitly distinct from requested summary date") InventorySnapshot inventorySnapshot) { }
}
