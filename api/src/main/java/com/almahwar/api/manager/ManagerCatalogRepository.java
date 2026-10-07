package com.almahwar.api.manager;

import com.almahwar.api.core.CoreConnectionBinding;
import com.almahwar.api.core.SpringSecurityContext;
import com.almahwar.api.error.ApiException;
import com.almahwar.api.error.ErrorCode;
import com.almahwar.api.web.PageResponse;
import com.almahwar.dao.*;
import com.almahwar.model.Product;
import com.almahwar.service.*;
import org.springframework.stereotype.Repository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static com.almahwar.api.manager.ManagerResponses.*;

/** Bounded stock projections alongside released core detail reads and visibility rules. */
@Repository
public class ManagerCatalogRepository extends BaseDao {
    private final ProductServiceImpl products;
    public ManagerCatalogRepository(CoreConnectionBinding binding,SpringSecurityContext security) {
        products=new ProductServiceImpl(new ProductDao(),new CategoryDao(),new BrandDao(),new UnitDao(),
                new StockLedger(new StockMovementDao()),new AuditLogDao(()->"API"),security);
    }
    public Product product(int id) { return products.findById(id).orElseThrow(()->new ApiException(ErrorCode.NOT_FOUND)); }
    public Inventory inventory(boolean cost) {
        return queryOne("""
                SELECT COUNT_BIG(*) AS products,
                    COALESCE(SUM(CAST(CASE WHEN quantity<=minimum_stock THEN 1 ELSE 0 END AS bigint)),0) AS low,
                    COALESCE(SUM(CAST(CASE WHEN quantity<=0 THEN 1 ELSE 0 END AS bigint)),0) AS empty,
                """+(cost?"COALESCE(SUM(CAST(quantity*purchase_price AS decimal(18,3))),0)":"CAST(NULL AS decimal(18,3))")
                +" AS value FROM dbo.Products WHERE is_active=1",rs -> new Inventory(rs.getLong("products"),rs.getLong("low"),
                rs.getLong("empty"),money(rs.getBigDecimal("value")))).orElseThrow();
    }
    public PageResponse<StockProduct> lowStock(ManagerPage input,boolean cost) {
        var page=input.paging();
        String order=input.order(Map.of("name","p.name_ar","code","p.product_code","quantity","p.quantity",
                "minimumStock","p.minimum_stock"),"quantity");
        List<Object> params=new ArrayList<>();
        String where=" WHERE p.is_active=1 AND p.quantity<=p.minimum_stock";
        String search=input.search();
        if(search!=null) {
            where+=" AND (p.name_ar LIKE ? OR p.name_en LIKE ? OR p.product_code LIKE ? OR p.barcode LIKE ?)";
            String like=likeContains(search);params.addAll(List.of(like,like,like,like));
        }
        String from=" FROM dbo.Products p JOIN dbo.Units u ON u.unit_id=p.unit_id";
        long count=queryLong("SELECT COUNT_BIG(*)"+from+where,params.toArray());
        params.add(page.offset());params.add(page.size());
        var rows=queryList("SELECT p.product_id,p.product_code,p.name_ar,u.name_ar AS unit,p.quantity,p.minimum_stock,"
                +(cost?"p.purchase_price":"CAST(NULL AS decimal(18,3))")+" AS cost"+from+where
                +" ORDER BY "+order+",p.product_id OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",rs -> new StockProduct(
                rs.getInt("product_id"),rs.getString("product_code"),rs.getString("name_ar"),rs.getString("unit"),
                quantity(rs.getBigDecimal("quantity")),quantity(rs.getBigDecimal("minimum_stock")),money(rs.getBigDecimal("cost"))),params.toArray());
        return PageResponse.of(rows,page,count);
    }
}
