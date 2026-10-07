package com.almahwar.api.admin;

import com.almahwar.api.core.CoreConnectionBinding;
import com.almahwar.api.core.SpringSecurityContext;
import com.almahwar.dao.AuditLogDao;
import com.almahwar.dao.RoleDao;
import com.almahwar.dao.UserDao;
import com.almahwar.service.UserService;
import com.almahwar.service.UserServiceImpl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
public class AdminCoreConfiguration {
    @Bean UserService administrationCore(CoreConnectionBinding binding, SpringSecurityContext security) {
        return new UserServiceImpl(new UserDao(),new RoleDao(),new AuditLogDao(()->"API"),security);
    }
}
