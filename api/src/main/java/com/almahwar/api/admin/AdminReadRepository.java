package com.almahwar.api.admin;

import com.almahwar.api.core.CoreConnectionBinding;
import com.almahwar.api.manager.ManagerPage;
import com.almahwar.api.web.PageResponse;
import com.almahwar.dao.BaseDao;
import org.springframework.stereotype.Repository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Bounded explicit projection: never selects a password, lock, failure count or fingerprint. */
@Repository
public class AdminReadRepository extends BaseDao {
    public AdminReadRepository(CoreConnectionBinding binding) { }
    private static final String COLUMNS="u.user_id,u.username,u.full_name,u.phone,u.email,r.role_code,r.role_name,"
            +"u.is_active,u.must_change_password,u.created_at,u.last_login_at";
    private static final String FROM=" FROM dbo.Users u JOIN dbo.Roles r ON r.role_id=u.role_id";
    private static AdminDtos.User map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AdminDtos.User(rs.getInt("user_id"),rs.getString("username"),rs.getString("full_name"),
                rs.getString("phone"),rs.getString("email"),rs.getString("role_code"),rs.getString("role_name"),
                rs.getBoolean("is_active"),rs.getBoolean("must_change_password"),getDateTime(rs,"created_at"),getDateTime(rs,"last_login_at"));
    }
    public Optional<AdminDtos.User> detail(int id) {
        return queryOne("SELECT "+COLUMNS+FROM+" WHERE u.user_id=?",AdminReadRepository::map,id);
    }
    public PageResponse<AdminDtos.User> users(ManagerPage input,String role,Boolean active) {
        var page=input.paging();String order=input.order(Map.of("name","u.full_name","username","u.username", "created","u.created_at"),"name");
        List<Object> parameters=new ArrayList<>();String where=" WHERE 1=1";
        if(input.search()!=null) {
            where+=" AND (u.username LIKE ? OR u.full_name LIKE ?)";
            String like=likeContains(input.search());parameters.addAll(List.of(like,like));
        }
        if(role!=null) { where+=" AND r.role_code=?";parameters.add(role); }
        if(active!=null) { where+=" AND u.is_active=?";parameters.add(active); }
        long count=queryOne("SELECT COUNT_BIG(*)"+FROM+where,rs->rs.getLong(1),parameters.toArray()).orElseThrow();
        parameters.add(page.offset());parameters.add(page.size());
        var rows=queryList("SELECT "+COLUMNS+FROM+where+" ORDER BY "+order+",u.user_id OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",AdminReadRepository::map,parameters.toArray());
        return PageResponse.of(rows,page,count);
    }
}
