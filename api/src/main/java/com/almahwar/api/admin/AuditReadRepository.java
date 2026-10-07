package com.almahwar.api.admin;

import com.almahwar.api.core.CoreConnectionBinding;
import com.almahwar.api.error.FieldValidationException;
import com.almahwar.api.manager.ManagerDateRange;
import com.almahwar.api.manager.ManagerPage;
import com.almahwar.api.web.PageResponse;
import com.almahwar.dao.AuditLogDao;
import com.almahwar.dao.BaseDao;
import org.springframework.stereotype.Repository;
import java.lang.reflect.Modifier;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

@Repository
public class AuditReadRepository extends BaseDao {
    private static final Set<String> EVENTS=events();
    private static final Set<String> CATEGORIES=Set.of("Users","Products","Customers","Suppliers","Sales","Purchases",
            "Expenses","Cash_Transactions","Sale_Returns","Purchase_Returns","Quotations","Company_Settings","System_Settings");
    public AuditReadRepository(CoreConnectionBinding binding) { }
    private static Set<String> events() {
        Set<String> result=new TreeSet<>(Set.of("API_LOGOUT_ALL","API_SESSION_REVOKED","API_REFRESH_REUSE"));
        for(var field:AuditLogDao.class.getFields()) {
            if(field.getType()==String.class && Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers())) {
                try { result.add((String)field.get(null)); } catch(IllegalAccessException e) { throw new ExceptionInInitializerError(e); }
            }
        }
        return Set.copyOf(result);
    }
    public LocalDate today() { return queryOne("SELECT CAST(SYSDATETIME() AS date)",rs->rs.getDate(1).toLocalDate()).orElseThrow(); }
    public PageResponse<AdminDtos.Audit> audit(ManagerDateRange range,ManagerPage input,Integer userId,String action,String category) {
        var page=input.paging();input.search();
        if(input.search()!=null || input.sort()!=null && !input.sort().equals("date,desc")) throw new FieldValidationException("sort","Audit uses date,desc; no free-text search.");
        if(userId!=null && userId<1) throw new FieldValidationException("userId","Positive user id required.");
        if(action!=null && !EVENTS.contains(action)) throw new FieldValidationException("action","Unknown event type.");
        if(category!=null && !CATEGORIES.contains(category)) throw new FieldValidationException("category","Unknown category.");
        String from=" FROM dbo.Audit_Log a LEFT JOIN dbo.Users u ON u.user_id=a.user_id WHERE a.created_at>=? AND a.created_at<?";
        List<Object> parameters=new ArrayList<>(List.of(range.from().atStartOfDay(),range.to().plusDays(1).atStartOfDay()));
        if(userId!=null) { from+=" AND a.user_id=?";parameters.add(userId); }
        if(action!=null) { from+=" AND a.action=?";parameters.add(action); }
        if(category!=null) { from+=" AND a.table_name=?";parameters.add(category); }
        long count=queryOne("SELECT COUNT_BIG(*)"+from,rs->rs.getLong(1),parameters.toArray()).orElseThrow();
        parameters.add(page.offset());parameters.add(page.size());
        var rows=queryList("SELECT a.log_id,a.created_at,a.user_id,u.username,u.full_name,a.action,a.table_name"+from
                +" ORDER BY a.created_at DESC,a.log_id DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",rs->{
            int actor=rs.getInt("user_id");Integer id=rs.wasNull()?null:actor;
            String event=rs.getString("action"),table=rs.getString("table_name");
            return new AdminDtos.Audit(rs.getLong("log_id"),getDateTime(rs,"created_at"),id,rs.getString("username"),rs.getString("full_name"),
                    EVENTS.contains(event)?event:"OTHER",table!=null && CATEGORIES.contains(table)?table:"OTHER");
        },parameters.toArray());
        return PageResponse.of(rows,page,count);
    }
}
