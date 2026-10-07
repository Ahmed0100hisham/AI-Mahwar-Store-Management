package com.almahwar.api.product;

import com.almahwar.api.web.PageQuery;
import com.almahwar.dao.ProductDao;
import com.almahwar.model.Product;
import com.almahwar.model.ProductFilter;

import java.util.List;

/** Per-call read adapter: the core supplies its authorized filter; only HTTP paging/sort stay in the API. */
final class PagedProductDao extends ProductDao {
    private final ProductRepository repository;
    private final boolean includeCost;
    private final ProductSort.Order order;
    private final PageQuery page;
    private long total;

    PagedProductDao(ProductRepository repository, boolean includeCost, ProductSort.Order order, PageQuery page) {
        this.repository = repository;
        this.includeCost = includeCost;
        this.order = order;
        this.page = page;
    }

    @Override
    public List<Product> search(ProductFilter filter) {
        boolean activeOnly = filter.status() == ProductFilter.ActiveStatus.ACTIVE;
        total = repository.count(filter.text(), activeOnly);
        return total == 0 || page.offset() >= total ? List.of()
                : repository.findPage(filter.text(), activeOnly, includeCost, order, page).stream()
                        .map(ProductRepository.ProductRow::toProduct).toList();
    }

    long total() {
        return total;
    }
}
