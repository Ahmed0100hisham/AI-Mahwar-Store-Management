package com.almahwar.api.manager;

import com.almahwar.api.web.PageResponse;
import com.almahwar.api.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import static com.almahwar.api.manager.ManagerDocumentResponses.*;

@RestController
@RequestMapping("/api/v1/manager")
@SecurityRequirement(name="bearer")
@Tag(name="Manager documents",description="Read-only. Live session required. Money/quantity strings have exactly 3 decimals. Lists use inclusive business dates (max 366), default today; page 0, size 20, maximum 100. All responses no-store. Optional protected fields are omitted.")
@ApiResponses({@ApiResponse(responseCode="400",description="Invalid input",content=@Content(schema=@Schema(implementation=ApiError.class))),
        @ApiResponse(responseCode="401",description="Authentication/session rejected",content=@Content(schema=@Schema(implementation=ApiError.class))),
        @ApiResponse(responseCode="403",description="Permission or restricted session",content=@Content(schema=@Schema(implementation=ApiError.class))),
        @ApiResponse(responseCode="404",description="Document not found",content=@Content(schema=@Schema(implementation=ApiError.class))),
        @ApiResponse(responseCode="503",description="Dependency unavailable",content=@Content(schema=@Schema(implementation=ApiError.class)))})
public class ManagerDocumentController {
    private final ManagerDocumentService service;
    public ManagerDocumentController(ManagerDocumentService service) { this.service=service; }
    @GetMapping("/invoices") @PreAuthorize("@managerAccess.allowed('invoices')")
    @Operation(summary="Invoice page",description="SALES_VIEW. Literal q searches invoice number and customer name/code/phones. Optional customerId/paymentMethod/status. Sort date (default asc), number, total with asc/desc and ID tie-breaker. Returns included through range.to. Historical cost needs SALES_COST_VIEW; stored gross profit is POSTED-only and needs SALES_PROFIT_VIEW.")
    public PageResponse<Invoice> invoices(@ParameterObject @ModelAttribute ManagerDateRange.Input range,
            @ParameterObject @ModelAttribute ManagerPage page,@ParameterObject @ModelAttribute ManagerDocumentFilter filter) {
        return service.invoices(range,page,filter);
    }
    @GetMapping("/invoices/{id}") @PreAuthorize("@managerAccess.allowed('invoices')")
    @Operation(summary="Invoice detail",description="SALES_VIEW. page/size independently page items; returnsPage/returnsSize page return headers (same defaults/bounds). Fixed ID order, no q/sort. Header returns and item returned quantities cover all recorded dates. Paid/remaining are original stored invoice snapshots, not recomputed customer debt. No line tax is fabricated; historical unit cost permission protected.")
    public InvoiceDetail invoice(@PathVariable int id,@ParameterObject @ModelAttribute ManagerPage page,
            @RequestParam(required=false) Integer returnsPage,@RequestParam(required=false) Integer returnsSize) {
        return service.invoice(id,page,returnsPage,returnsSize);
    }
    @GetMapping("/quotations") @PreAuthorize("@managerAccess.allowed('quotations')")
    @Operation(summary="Quotation tracking page",description="QUOTATIONS_VIEW. Literal q searches number, registered/prospect name/phones/code. Optional customerId/status/pastValidity. Sort date (default asc), number, total, validUntil plus ID. Six released stored statuses; pastValidity is independent, valid_until < businessDate. Reads never transition expiry. Linked sale additionally needs SALES_VIEW. No quotation tax exists.")
    public PageResponse<Quotation> quotations(@ParameterObject @ModelAttribute ManagerDateRange.Input range,
            @ParameterObject @ModelAttribute ManagerPage page,@ParameterObject @ModelAttribute ManagerDocumentFilter filter) {
        return service.quotations(range,page,filter);
    }
    @GetMapping("/quotations/{id}") @PreAuthorize("@managerAccess.allowed('quotations')")
    @Operation(summary="Quotation detail",description="QUOTATIONS_VIEW. page/size page items in fixed ID order, no q/sort. Stored status and pure validity flag; stored conversion link is not proof of a posted sale. Prospect and registered customer are explicitly separate. Notes and internal request/security fields excluded.")
    public QuotationDetail quotation(@PathVariable int id,@ParameterObject @ModelAttribute ManagerPage page) {
        return service.quotation(id,page);
    }
    @GetMapping("/daily-summary") @PreAuthorize("@managerAccess.allowed('dashboard')")
    @Operation(summary="Permission-filtered daily summary",description="DASHBOARD. Optional ISO date, default SQL business today. Reuses Phase 3 sales/expenses/cashbox with their existing permissions and historical profit rules. Omitted sections reveal no placeholder values. Inventory snapshot, when authorized, is current and separately dated; never a historical inventory assertion.")
    public Daily daily(@RequestParam(required=false) LocalDate date) { return service.daily(date); }
}
