package com.almahwar.api.admin;

import com.almahwar.api.manager.ManagerDateRange;
import com.almahwar.api.manager.ManagerPage;
import com.almahwar.api.web.PageResponse;
import com.almahwar.model.Permission;
import org.springframework.stereotype.Service;

@Service
public class AuditQueryService {
    private final AdminAccess access;
    private final AuditReadRepository reads;
    public AuditQueryService(AdminAccess access,AuditReadRepository reads) { this.access=access;this.reads=reads; }
    public PageResponse<AdminDtos.Audit> audit(ManagerDateRange.Input dates,ManagerPage page,Integer userId,String action,String category) {
        access.require(Permission.AUDIT_LOG);
        return reads.audit(ManagerDateRange.resolve(dates,366,reads::today),page,userId,action,category);
    }
}
