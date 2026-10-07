package com.almahwar.api.manager;

import com.almahwar.api.core.CoreConnectionBinding;
import com.almahwar.api.core.SpringSecurityContext;
import com.almahwar.api.error.ApiException;
import com.almahwar.api.error.ErrorCode;
import com.almahwar.api.error.FieldValidationException;
import com.almahwar.api.web.PageResponse;
import com.almahwar.dao.*;
import com.almahwar.model.PartyType;
import com.almahwar.service.*;
import org.springframework.stereotype.Repository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static com.almahwar.api.manager.ManagerResponses.*;

/** Paged projections of core account rules; no security/notes/addresses or unbounded statement loading. */
@Repository
public class ManagerPartyRepository extends BaseDao {
    private final CustomerServiceImpl customers;
    private final SupplierServiceImpl suppliers;
    private final AccountLedgerDao ledger=new AccountLedgerDao();
    public ManagerPartyRepository(CoreConnectionBinding binding,SpringSecurityContext security) {
        var customerDao=new CustomerDao();var supplierDao=new SupplierDao();
        var accounts=new AccountLedger(ledger,customerDao,supplierDao);var audit=new AuditLogDao(()->"API");
        customers=new CustomerServiceImpl(customerDao,accounts,audit,security);
        suppliers=new SupplierServiceImpl(supplierDao,accounts,audit,security);
    }
    private static String table(PartyType type) { return type==PartyType.CUSTOMER?"dbo.Customers":"dbo.Suppliers"; }
    private static String id(PartyType type) { return type==PartyType.CUSTOMER?"customer_id":"supplier_id"; }
    private static String code(PartyType type) { return type==PartyType.CUSTOMER?"customer_code":"supplier_code"; }
    private static String effect(PartyType type) { return type==PartyType.CUSTOMER?"l.debit-l.credit":"l.credit-l.debit"; }
    public Party detail(PartyType type,int id) {
        if(type==PartyType.CUSTOMER) {
            var c=customers.findById(id).orElseThrow(()->new ApiException(ErrorCode.NOT_FOUND));
            return new Party(id,c.getCustomerCode(),c.getName(),c.getPhone(),c.getArea(),c.isActive(),money(c.getBalance()));
        }
        var s=suppliers.findById(id).orElseThrow(()->new ApiException(ErrorCode.NOT_FOUND));
        return new Party(id,s.getSupplierCode(),s.getName(),s.getPhone(),s.getArea(),s.isActive(),money(s.getBalance()));
    }
    public PartyList list(PartyType type,ManagerPage input,boolean balance,boolean outstanding) {
        var page=input.paging();
        if (!balance && input.sort()!=null && input.sort().startsWith("balance"))
            throw new FieldValidationException("sort","Balance sorting requires balance access.");
        String balanceColumn=outstanding?"b.balance":"p.balance";
        var fields=new java.util.HashMap<>(Map.of("name","p.name","code","p."+code(type)));
        if(balance) fields.put("balance",balanceColumn);
        String order=input.order(fields,outstanding?"balance,desc":"name");
        String from=" FROM "+table(type)+" p";
        if(outstanding) from+=" JOIN (SELECT "+id(type)+" AS party_id,SUM("+effect(type)+") AS balance FROM dbo.Account_Ledger l WHERE l.party_type=? GROUP BY "+id(type)+") b ON b.party_id=p."+id(type);
        List<Object> params=new ArrayList<>();if(outstanding) params.add(type.name());
        String where=outstanding?" WHERE b.balance>0":" WHERE 1=1";
        String search=input.search();
        if(search!=null) {
            where+=" AND (p.name LIKE ? OR p."+code(type)+" LIKE ? OR p.phone LIKE ? OR p.phone2 LIKE ? OR p.area LIKE ?)";
            String like=likeContains(search);params.addAll(List.of(like,like,like,like,like));
        }
        var totals=queryOne("SELECT COUNT_BIG(*) AS n,"+(outstanding?"COALESCE(SUM(b.balance),0)":"CAST(NULL AS decimal(18,3))")
                +" AS total"+from+where,rs -> new Object[]{rs.getLong("n"),money(rs.getBigDecimal("total"))},params.toArray()).orElseThrow();
        params.add(page.offset());params.add(page.size());
        var rows=queryList("SELECT p."+id(type)+" AS id,p."+code(type)+" AS code,p.name,p.phone,p.area,p.is_active,"
                +(balance?balanceColumn:"CAST(NULL AS decimal(18,3))")+" AS balance"+from+where
                +" ORDER BY "+order+",p."+id(type)+" OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",rs -> new Party(rs.getInt("id"),rs.getString("code"),
                rs.getString("name"),rs.getString("phone"),rs.getString("area"),rs.getBoolean("is_active"),money(rs.getBigDecimal("balance"))),params.toArray());
        return new PartyList(PageResponse.of(rows,page,(Long)totals[0]),(String)totals[1]);
    }
    public Account account(PartyType type,int partyId,ManagerDateRange range,ManagerPage input) {
        var page=input.paging();
        if(input.search()!=null || (input.sort()!=null && !input.sort().equals("date,asc")))
            throw new FieldValidationException("sort","Accounts use date,asc and do not support text filters.");
        String where=" FROM dbo.Account_Ledger l WHERE l."+id(type)+"=? AND l.entry_date>=? AND l.entry_date<?";
        var totals=queryOne("SELECT COUNT_BIG(*) AS n,COALESCE(SUM(l.debit),0) AS debit,COALESCE(SUM(l.credit),0) AS credit"+where,
                rs -> new Object[]{rs.getLong("n"),rs.getBigDecimal("debit"),rs.getBigDecimal("credit")},
                partyId,range.from().atStartOfDay(),range.to().plusDays(1).atStartOfDay()).orElseThrow();
        var opening=ledger.balanceBefore(type,partyId,range.from());
        var debit=(java.math.BigDecimal)totals[1];var credit=(java.math.BigDecimal)totals[2];
        var entries=queryList("""
                WITH history AS (
                    SELECT l.entry_id,l.entry_date,l.entry_type,l.reference_no,l.debit,l.credit,
                        SUM(%s) OVER(ORDER BY l.entry_date,l.entry_id ROWS UNBOUNDED PRECEDING) AS running
                    FROM dbo.Account_Ledger l WHERE l.%s=? AND l.entry_date<?
                )
                SELECT entry_date,entry_type,reference_no,debit,credit,running FROM history
                WHERE entry_date>=? ORDER BY entry_date,entry_id OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
                """.formatted(effect(type),id(type)),rs -> new AccountEntry(getDateTime(rs,"entry_date"),rs.getString("entry_type"),
                rs.getString("reference_no"),money(rs.getBigDecimal("debit")),money(rs.getBigDecimal("credit")),money(rs.getBigDecimal("running"))),
                partyId,range.to().plusDays(1).atStartOfDay(),range.from().atStartOfDay(),page.offset(),page.size());
        return new Account(partyId,range,money(opening),money(debit),money(credit),money(opening.add(type.balanceEffect(debit,credit))),
                PageResponse.of(entries,page,(Long)totals[0]));
    }
}
