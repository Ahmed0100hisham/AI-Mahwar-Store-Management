package com.almahwar.api.product;

import com.almahwar.api.error.ApiError;
import com.almahwar.api.web.PageQuery;
import com.almahwar.api.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Products (read-only proof of the architecture). No SQL here: everything goes through {@link ProductQueryService}. */
@RestController
@RequestMapping("/api/v1/products")
@Tag(name = "Products")
public class ProductController {

    private final ProductQueryService service;

    public ProductController(ProductQueryService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List products (paged)", security = @SecurityRequirement(name = "bearer"),
            description = "Needs PRODUCTS_VIEW or PRODUCTS. purchasePrice only with PRODUCT_COST. "
                    + "includeInactive is honoured only with PRODUCTS. Money and quantities are decimal strings.")
    @ApiResponse(responseCode = "200", description = "One page of products")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR (page, size, sort, q)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "401", description = "UNAUTHORIZED / SESSION_REVOKED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "FORBIDDEN / PASSWORD_CHANGE_REQUIRED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public PageResponse<ProductResponse> list(
            @Parameter(description = "Search in Arabic / English name, code and barcode")
            @RequestParam(required = false) @Size(max = 100, message = "نص البحث طويل جدًا.") String q,
            @RequestParam(defaultValue = "false") boolean includeInactive,
            @Parameter(description = "name | code | salePrice | quantity, optionally ,asc or ,desc")
            @RequestParam(defaultValue = "name") @Size(max = 30) String sort,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "يجب ألا يقل عن 0.")
            @Max(value = PageQuery.MAX_PAGE, message = "رقم الصفحة كبير جدًا.") int page,
            @RequestParam(defaultValue = "" + PageQuery.DEFAULT_SIZE) @Min(value = 1, message = "يجب ألا يقل عن 1.")
            @Max(value = PageQuery.MAX_SIZE, message = "الحد الأقصى 100.") int size) {
        return service.list(q, includeInactive, sort, new PageQuery(page, size));
    }
}
