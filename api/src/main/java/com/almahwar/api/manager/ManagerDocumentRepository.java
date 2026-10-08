package com.almahwar.api.manager;

import com.almahwar.api.core.CoreConnectionBinding;
import com.almahwar.api.error.ApiException;
import com.almahwar.api.error.ErrorCode;
import com.almahwar.api.web.PageQuery;
import com.almahwar.api.web.PageResponse;
import com.almahwar.dao.BaseDao;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static com.almahwar.api.manager.ManagerDocumentResponses.*;
import static com.almahwar.api.manager.ManagerResponses.*;

/** Only explicit read projections. Does not invoke quotation service reads, which can expire documents. */
@Repository
public class ManagerDocumentRepository extends BaseDao {
    public ManagerDocumentRepository(CoreConnectionBinding binding) { }
    private static final String INVOICE_FROM=" FROM dbo.Sales s JOIN dbo.Customers c ON c.customer_id=s.customer_id JOIN dbo.Users u ON u.user_id=s.user_id";
    private static final String QUOTATION_FROM=" FROM dbo.Quotations q LEFT JOIN dbo.Customers c ON c.customer_id=q.customer_id JOIN dbo.Users u ON u.user_id=q.user_id";
    private static final String INVOICE_FIELDS="s.sale_id,s.invoice_no,s.sale_date,s.customer_id,c.customer_code,c.name,c.phone,s.user_id,u.full_name,s.status,s.price_type,s.payment_method,s.payment_status,s.subtotal,s.discount_amount,s.tax_amount,s.total_amount,s.paid_amount,s.remaining_amount";
    private String invoiceFields(boolean cost,boolean profit) {
        return "SELECT "+INVOICE_FIELDS+","+(cost?"s.cost_total":"CAST(NULL AS decimal(18,3))")+" AS cost,"
                +(profit?"CASE WHEN s.status='POSTED' THEN s.gross_profit ELSE NULL END":"CAST(NULL AS decimal(18,3))")+" AS profit,COALESCE(r.returned,0) AS returned,COALESCE(r.refunded,0) AS refunded,s.total_amount-COALESCE(r.returned,0) AS net";
    }
    private static String returnJoin(boolean cutoff) {
        return " OUTER APPLY (SELECT SUM(sr.total_amount) AS returned,SUM(sr.refund_amount) AS refunded FROM dbo.Sale_Returns sr WHERE sr.sale_id=s.sale_id"
                +(cutoff?" AND sr.return_date<?":"")+") r";
    }
    public PageResponse<Invoice> invoices(ManagerDateRange range,ManagerPage input,ManagerDocumentFilter filter,boolean cost,boolean profit) {
        var page=input.paging();List<Object> params=new ArrayList<>();
        String where=where(true,range,input,filter,null,params);
        long count=queryLong("SELECT COUNT_BIG(*)"+INVOICE_FROM+where,params.toArray());
        List<Object> selected=new ArrayList<>();selected.add(range.to().plusDays(1).atStartOfDay());selected.addAll(params);
        selected.add(page.offset());selected.add(page.size());
        String order=input.order(Map.of("date","s.sale_date","number","s.invoice_no","total","s.total_amount"),"date");
        return PageResponse.of(queryList(invoiceFields(cost,profit)+INVOICE_FROM+returnJoin(true)+where
                +" ORDER BY "+order+",s.sale_id OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",rs->invoice(rs,range.to()),selected.toArray()),page,count);
    }
    public InvoiceDetail invoice(int id,PageQuery items,PageQuery returns,boolean cost,boolean profit) {
        var header=queryOne(invoiceFields(cost,profit)+INVOICE_FROM+returnJoin(false)+" WHERE s.sale_id=?",rs->invoice(rs,null),id)
                .orElseThrow(()->new ApiException(ErrorCode.NOT_FOUND));
        long count=queryLong("SELECT COUNT_BIG(*) FROM dbo.Sale_Items WHERE sale_id=?",id);
        var lines=queryList("SELECT i.sale_item_id,i.product_id,p.product_code,p.name_ar,u.name_ar AS unit,i.quantity,i.unit_price,i.discount_amount,i.line_total,"
                +(cost?"i.unit_cost":"CAST(NULL AS decimal(18,3))")+" AS cost,COALESCE(r.quantity,0) AS returned"
                +" FROM dbo.Sale_Items i JOIN dbo.Products p ON p.product_id=i.product_id JOIN dbo.Units u ON u.unit_id=p.unit_id"
                +" OUTER APPLY (SELECT SUM(ri.quantity) AS quantity FROM dbo.Sale_Return_Items ri WHERE ri.sale_item_id=i.sale_item_id) r"
                +" WHERE i.sale_id=? ORDER BY i.sale_item_id OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",rs->new InvoiceLine(
                        rs.getInt("sale_item_id"),rs.getInt("product_id"),rs.getString("product_code"),rs.getString("name_ar"),rs.getString("unit"),
                        quantity(rs.getBigDecimal("quantity")),money(rs.getBigDecimal("unit_price")),money(rs.getBigDecimal("discount_amount")),
                        money(rs.getBigDecimal("line_total")),quantity(rs.getBigDecimal("returned")),money(rs.getBigDecimal("cost"))),id,items.offset(),items.size());
        long returnedCount=queryLong("SELECT COUNT_BIG(*) FROM dbo.Sale_Returns WHERE sale_id=?",id);
        var returned=queryList("SELECT return_id,return_no,return_date,total_amount,refund_amount,refund_method FROM dbo.Sale_Returns WHERE sale_id=? ORDER BY return_id OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                rs->new Returned(rs.getInt("return_id"),rs.getString("return_no"),getDateTime(rs,"return_date"),money(rs.getBigDecimal("total_amount")),
                        money(rs.getBigDecimal("refund_amount")),rs.getString("refund_method")),id,returns.offset(),returns.size());
        return new InvoiceDetail(header,PageResponse.of(lines,items,count),PageResponse.of(returned,returns,returnedCount));
    }
    private static Invoice invoice(ResultSet rs,LocalDate cutoff) throws SQLException {
        return new Invoice(rs.getInt("sale_id"),rs.getString("invoice_no"),getDateTime(rs,"sale_date"),
                new Person(rs.getInt("customer_id"),rs.getString("customer_code"),rs.getString("name"),rs.getString("phone")),
                new Creator(rs.getInt("user_id"),rs.getString("full_name")),rs.getString("status"),rs.getString("price_type"),
                rs.getString("payment_method"),rs.getString("payment_status"),money(rs.getBigDecimal("subtotal")),money(rs.getBigDecimal("discount_amount")),
                money(rs.getBigDecimal("tax_amount")),money(rs.getBigDecimal("total_amount")),money(rs.getBigDecimal("paid_amount")),money(rs.getBigDecimal("remaining_amount")),
                money(rs.getBigDecimal("returned")),money(rs.getBigDecimal("refunded")),money(rs.getBigDecimal("net")),cutoff,money(rs.getBigDecimal("cost")),money(rs.getBigDecimal("profit")));
    }
    private static String quotationFields(boolean link) {
        return "SELECT q.quotation_id,q.quotation_no,q.quotation_date,q.valid_until,q.customer_id,c.customer_code,c.name,c.phone,q.customer_name AS prospect_name,q.customer_phone AS prospect_phone,q.user_id,u.full_name,q.status,q.price_type,q.subtotal,q.discount_amount,q.total_amount,q.sent_at,q.decided_at,"
                +(link?"s.sale_id AS linked_id,s.invoice_no AS linked_no,s.status AS linked_status":"CAST(NULL AS int) AS linked_id,CAST(NULL AS nvarchar(30)) AS linked_no,CAST(NULL AS varchar(20)) AS linked_status");
    }
    private static String quotationFrom(boolean link) {
        return QUOTATION_FROM+(link?" LEFT JOIN dbo.Sales s ON s.sale_id=q.converted_sale_id":"");
    }
    public PageResponse<Quotation> quotations(ManagerDateRange range,ManagerPage input,ManagerDocumentFilter filter,LocalDate today,boolean link) {
        var page=input.paging();List<Object> params=new ArrayList<>();String where=where(false,range,input,filter,today,params);
        long count=queryLong("SELECT COUNT_BIG(*)"+QUOTATION_FROM+where,params.toArray());
        params.add(page.offset());params.add(page.size());
        String order=input.order(Map.of("date","q.quotation_date","number","q.quotation_no","total","q.total_amount","validUntil","q.valid_until"),"date");
        return PageResponse.of(queryList(quotationFields(link)+quotationFrom(link)+where+" ORDER BY "+order+",q.quotation_id OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                rs->quotation(rs,today),params.toArray()),page,count);
    }
    public QuotationDetail quotation(int id,PageQuery page,LocalDate today,boolean link) {
        var header=queryOne(quotationFields(link)+quotationFrom(link)+" WHERE q.quotation_id=?",rs->quotation(rs,today),id)
                .orElseThrow(()->new ApiException(ErrorCode.NOT_FOUND));
        long count=queryLong("SELECT COUNT_BIG(*) FROM dbo.Quotation_Items WHERE quotation_id=?",id);
        var lines=queryList("SELECT i.quotation_item_id,i.product_id,p.product_code,p.name_ar,u.name_ar AS unit,i.quantity,i.unit_price,i.discount_amount,i.line_total FROM dbo.Quotation_Items i JOIN dbo.Products p ON p.product_id=i.product_id JOIN dbo.Units u ON u.unit_id=p.unit_id WHERE i.quotation_id=? ORDER BY i.quotation_item_id OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                rs->new QuotationLine(rs.getInt("quotation_item_id"),rs.getInt("product_id"),rs.getString("product_code"),rs.getString("name_ar"),rs.getString("unit"),
                        quantity(rs.getBigDecimal("quantity")),money(rs.getBigDecimal("unit_price")),money(rs.getBigDecimal("discount_amount")),money(rs.getBigDecimal("line_total"))),id,page.offset(),page.size());
        return new QuotationDetail(header,PageResponse.of(lines,page,count));
    }
    private static Quotation quotation(ResultSet rs,LocalDate today) throws SQLException {
        var validity=getDate(rs,"valid_until");var core=new com.almahwar.model.Quotation();core.setValidUntil(validity);
        Integer linked=getInteger(rs,"linked_id");
        return new Quotation(rs.getInt("quotation_id"),rs.getString("quotation_no"),getDateTime(rs,"quotation_date"),validity,today,core.isPastValidity(today),rs.getString("status"),
                new Person(getInteger(rs,"customer_id"),rs.getString("customer_code"),rs.getString("name"),rs.getString("phone")),rs.getString("prospect_name"),rs.getString("prospect_phone"),
                new Creator(rs.getInt("user_id"),rs.getString("full_name")),rs.getString("price_type"),money(rs.getBigDecimal("subtotal")),money(rs.getBigDecimal("discount_amount")),money(rs.getBigDecimal("total_amount")),
                getDateTime(rs,"sent_at"),getDateTime(rs,"decided_at"),linked==null?null:new SaleReference(linked,rs.getString("linked_no"),rs.getString("linked_status")));
    }
    private static String where(boolean invoice,ManagerDateRange range,ManagerPage input,ManagerDocumentFilter filter,LocalDate today,List<Object> params) {
        String prefix=invoice?"s":"q",date=invoice?"sale_date":"quotation_date",number=invoice?"invoice_no":"quotation_no";
        String where=" WHERE "+prefix+"."+date+">=? AND "+prefix+"."+date+"<?";
        params.add(range.from().atStartOfDay());params.add(range.to().plusDays(1).atStartOfDay());
        if(filter.customerId()!=null) { where+=" AND "+prefix+".customer_id=?";params.add(filter.customerId()); }
        if(filter.status()!=null) { where+=" AND "+prefix+".status=?";params.add(filter.status()); }
        if(filter.paymentMethod()!=null) { where+=" AND s.payment_method=?";params.add(filter.paymentMethod()); }
        if(filter.pastValidity()!=null) {
            where+=filter.pastValidity()?" AND q.valid_until<?":" AND (q.valid_until IS NULL OR q.valid_until>=?)";params.add(today);
        }
        if(input.search()!=null) {
            where+=" AND ("+prefix+"."+number+" LIKE ? OR c.name LIKE ? OR c.customer_code LIKE ? OR c.phone LIKE ? OR c.phone2 LIKE ?"
                    +(invoice?"":" OR q.customer_name LIKE ? OR q.customer_phone LIKE ?")+")";
            String like=likeContains(input.search());for(int i=0;i<(invoice?5:7);i++) params.add(like);
        }
        return where;
    }
}
