package com.almahwar.service;

import com.almahwar.model.DatabaseHealth;

/**
 * Startup health check of the database (no login needed, read only: it never repairs, migrates, creates users or
 * changes data). Run before the login screen offers the login or the first-administrator setup.
 */
public interface HealthCheckService {

    /** Configuration, server, login, database state, schema version, critical tables, active administrator. */
    DatabaseHealth check();
}
