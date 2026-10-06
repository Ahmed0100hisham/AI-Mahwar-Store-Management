package com.almahwar.api.security;

import com.almahwar.api.config.ApiProperties;
import org.springframework.web.cors.CorsConfigurationSource;

/** Test access to the package-private CORS factory of {@link SecurityConfig}. */
public class SecurityConfigAccess {

    public CorsConfigurationSource cors(ApiProperties properties) {
        return new SecurityConfig().corsConfigurationSource(properties);
    }
}
