package com.almahwar.api.manager;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@RestControllerAdvice(assignableTypes={ManagerController.class,com.almahwar.api.admin.AdminController.class,com.almahwar.api.admin.AuditController.class})
public class ManagerCacheAdvice implements ResponseBodyAdvice<Object> {
    @Override public boolean supports(MethodParameter method,Class<? extends HttpMessageConverter<?>> converter) { return true; }
    @Override public Object beforeBodyWrite(Object body,MethodParameter method,MediaType type,Class<? extends HttpMessageConverter<?>> converter,
                                             ServerHttpRequest request,ServerHttpResponse response) {
        response.getHeaders().setCacheControl("no-store");return body;
    }
}
