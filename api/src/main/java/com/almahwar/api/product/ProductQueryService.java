package com.almahwar.api.product;

import com.almahwar.api.security.CurrentUser;
import com.almahwar.api.security.Permission;
import com.almahwar.api.web.PageQuery;
import com.almahwar.api.web.PageResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Product reading with the desktop's {@code ProductServiceImpl.search} rules:
 * <ul>
 *   <li>needs {@code PRODUCTS_VIEW} or {@code PRODUCTS};</li>
 *   <li>view-only users ({@code PRODUCTS_VIEW} without {@code PRODUCTS}) see active products only;</li>
 *   <li>the purchase price (cost) only with {@code PRODUCT_COST}.</li>
 * </ul>
 * Pattern for every service: authorization here ({@code @PreAuthorize}, close to the data), and the transaction
 * boundary here ({@code @Transactional}) — read-only here; future mutations (sales, purchases, …) run their whole
 * business operation in one read-write transaction in their service method, never across HTTP calls.
 */
@Service
public class ProductQueryService {

    private final ProductRepository products;
    private final CurrentUser currentUser;

    public ProductQueryService(ProductRepository products, CurrentUser currentUser) {
        this.products = products;
        this.currentUser = currentUser;
    }

    @PreAuthorize("hasAnyAuthority('PERM_PRODUCTS_VIEW', 'PERM_PRODUCTS')")
    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> list(String text, boolean includeInactive, String sort, PageQuery page) {
        ProductSort.Order order = ProductSort.parse(sort);
        boolean activeOnly = !(includeInactive && currentUser.has(Permission.PRODUCTS));
        boolean includeCost = currentUser.has(Permission.PRODUCT_COST);
        String search = text == null || text.isBlank() ? null : text.trim();

        long total = products.count(search, activeOnly);
        List<ProductResponse> items = total == 0 || page.offset() >= total ? List.of()
                : products.findPage(search, activeOnly, includeCost, order, page).stream()
                        .map(r -> ProductResponse.of(r, includeCost)).toList();
        return PageResponse.of(items, page, total);
    }
}
