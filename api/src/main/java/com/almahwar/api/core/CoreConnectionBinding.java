package com.almahwar.api.core;

import com.almahwar.dao.ConnectionSource;
import com.almahwar.dao.ConnectionProvider;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/** One API application per JVM: the released core has a process-wide provider, installed before serving requests. */
@Component
public final class CoreConnectionBinding implements DisposableBean {
    private final DataSourceConnectionProvider provider;
    private final ConnectionProvider previous;

    public CoreConnectionBinding(DataSource dataSource) {
        previous = ConnectionSource.current();
        provider = new DataSourceConnectionProvider(dataSource);
        ConnectionSource.use(provider);
    }

    @Override
    public void destroy() {
        // Do not reset a replacement owned by another context (also useful for isolated test contexts).
        if (ConnectionSource.current() == provider) {
            ConnectionSource.use(previous);
        }
    }
}
