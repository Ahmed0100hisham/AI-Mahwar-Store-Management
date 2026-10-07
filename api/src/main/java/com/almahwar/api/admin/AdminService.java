package com.almahwar.api.admin;

import com.almahwar.api.error.ApiException;
import com.almahwar.api.error.ErrorCode;
import com.almahwar.api.error.FieldValidationException;
import com.almahwar.api.manager.ManagerPage;
import com.almahwar.api.web.PageResponse;
import com.almahwar.model.UserAccount;
import com.almahwar.service.UserService;
import com.almahwar.util.PasswordHasher;
import org.springframework.stereotype.Service;
import java.util.List;
import static com.almahwar.model.Permission.*;

@Service
public class AdminService {
    private final AdminAccess access;
    private final UserService core;
    private final AdminReadRepository reads;
    private final AdminSessionRepository sessions;
    public AdminService(AdminAccess access,UserService core,AdminReadRepository reads,AdminSessionRepository sessions) {
        this.access=access;this.core=core;this.reads=reads;this.sessions=sessions;
    }
    private void id(int id) { if(id<1) throw new FieldValidationException("id","Positive user id required."); }
    private AdminDtos.User existing(int id) { id(id);return reads.detail(id).orElseThrow(()->new ApiException(ErrorCode.NOT_FOUND)); }
    public PageResponse<AdminDtos.User> users(ManagerPage page,String role,Boolean active) {
        access.require(USERS_VIEW);page.validate("name","username","created");
        if(role!=null && !core.roles().containsKey(role)) throw new FieldValidationException("role","Select an assignable role.");
        return reads.users(page,role,active);
    }
    public AdminDtos.User detail(int id) { access.require(USERS_VIEW);return existing(id); }
    public List<AdminDtos.Role> roles() {
        access.require(USERS_VIEW);return core.roles().entrySet().stream().map(r->new AdminDtos.Role(r.getKey(),r.getValue())).toList();
    }
    public List<AdminDtos.Permission> permissions() {
        access.require(USERS_VIEW);return core.permissionMatrix().stream().map(p->new AdminDtos.Permission(p.permission().name(),p.permission().getLabelAr(),p.group(),p.roles())).toList();
    }
    public AdminDtos.User create(AdminDtos.Create request) {
        access.require(USERS_CREATE);
        char[] password=request.password().toCharArray(),confirm=request.confirmPassword().toCharArray();
        try {
            return AdminDtos.User.from(core.create(new UserAccount.NewUser(request.username(),request.fullName(),request.phone(),request.email(),request.roleCode(),true),password,confirm));
        } finally { PasswordHasher.wipe(password);PasswordHasher.wipe(confirm); }
    }
    public AdminDtos.User edit(int id,AdminDtos.Edit request) {
        access.require(USERS_EDIT);existing(id);
        if(request.active()==null) throw new FieldValidationException("active","Explicit active state required.");
        return sessions.mutate(id,request.active(),()->AdminDtos.User.from(core.update(new UserAccount.Changes(id,request.fullName(),request.phone(),request.email(),request.roleCode(),request.active()))));
    }
    public AdminDtos.User active(int id,boolean active) {
        access.require(USERS_EDIT);existing(id);
        return sessions.mutate(id,active,()->AdminDtos.User.from(core.setActive(id,active)));
    }
    public AdminDtos.User reset(int id,AdminDtos.Reset request) {
        access.require(USERS_RESET_PASSWORD);existing(id);
        char[] password=request.password().toCharArray(),confirm=request.confirmPassword().toCharArray();
        try { return sessions.mutate(id,false,()->AdminDtos.User.from(core.resetPassword(id,password,confirm,true))); }
        finally { PasswordHasher.wipe(password);PasswordHasher.wipe(confirm); }
    }
}
