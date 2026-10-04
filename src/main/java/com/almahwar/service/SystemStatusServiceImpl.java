package com.almahwar.service;

import com.almahwar.config.AppConfig;
import com.almahwar.config.DatabaseConnection;

/** {@link SystemStatusService} for direct SQL Server access. */
public class SystemStatusServiceImpl implements SystemStatusService {

    private final AppConfig config;

    public SystemStatusServiceImpl(AppConfig config) {
        this.config = config;
    }

    @Override
    public boolean isBackendReachable() {
        return DatabaseConnection.testConnection();
    }

    @Override
    public String backendDescription() {
        return config.get("db.host") + " / " + config.get("db.name");
    }
}
