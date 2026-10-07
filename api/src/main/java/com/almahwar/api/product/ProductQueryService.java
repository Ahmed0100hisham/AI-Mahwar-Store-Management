package com.almahwar.api.product;

import com.almahwar.api.core.SpringSecurityContext;
import com.almahwar.api.web.PageQuery;
import com.almahwar.api.web.PageResponse;
import com.almahwar.dao.AuditLogDao;
import com.almahwar.dao.BrandDao;
import com.almahwar.dao.CategoryDao;
import com.almahwar.dao.StockMovementDao;
import com.almahwar.dao.UnitDao;
import com.almahwar.model.Permission;
import com.almahwar.model.ProductFilter;
import com.almahwar.service.ProductServiceImpl;
import com.almahwar.service.StockLedger;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/** HTTP paging and DTO orchestration. The released core owns authorization and product visibility. */
@Service
public class ProductQueryService {
    private final ProductRepository products;
    private final SpringSecurityContext security;

    public ProductQueryService(ProductRepository products, SpringSecurityContext security) {
        this.products = products;
        this.security = security;
    }

    @PreAuthorize("hasAnyAuthority('PERM_PRODUCTS_VIEW', 'PERM_PRODUCTS')")
    public PageResponse<ProductResponse> list(String text, boolean includeInactive, String sort, PageQuery page) {
        ProductSort.Order order = ProductSort.parse(sort);
        boolean includeCost = security.hasPermission(Permission.PRODUCT_COST);
        PagedProductDao dao = new PagedProductDao(products, includeCost, order, page);
        ProductServiceImpl core = new ProductServiceImpl(dao, new CategoryDao(), new BrandDao(), new UnitDao(),
                new StockLedger(new StockMovementDao()), new AuditLogDao(() -> "API"), security);
        ProductFilter filter = ProductFilter.search(text).withStatus(includeInactive
                ? ProductFilter.ActiveStatus.ALL : ProductFilter.ActiveStatus.ACTIVE);
        var items = core.search(filter).stream().map(p -> ProductResponse.of(p, includeCost)).toList();
        return PageResponse.of(items, page, dao.total());
    }
}
