package com.despegar.jdbc.galera.consistency;

import com.despegar.jdbc.galera.GaleraStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * This proxy is responsible for managing wsrep_sync_wait (wsrep_causal_reads on earlier mariaDB versions) at connection level.
 * When you get a connection, the proxy sets the connection to the desired wsrep_sync_wait level.
 * The proxy releases wsrep_sync_wait value to mariadb global setting when closing connections.
 */
public class GaleraProxyConnection implements InvocationHandler {
    private static final Logger LOG = LoggerFactory.getLogger(GaleraProxyConnection.class);

    private static final String CLOSE_METHOD = "close";

    private Connection underlyingConnection;
    private String globalConsistencyLevel;
    private boolean supportsSyncWait;

    public GaleraProxyConnection(Connection conn, ConsistencyLevel connectionConsistencyLevel, GaleraStatus galeraStatus) throws SQLException {
        this.underlyingConnection = conn;
        this.supportsSyncWait = galeraStatus.supportsSyncWait();
        this.globalConsistencyLevel = galeraStatus.getGlobalConsistencyLevel();

        validate(connectionConsistencyLevel);

        ConsistencyLevelSupport.set(underlyingConnection, connectionConsistencyLevel.value, this.supportsSyncWait);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (CLOSE_METHOD.equals(method.getName())) {
            LOG.info("Setting wsrep_sync_wait to global default before closing connection {}", globalConsistencyLevel);
            ConsistencyLevelSupport.set(underlyingConnection, globalConsistencyLevel, this.supportsSyncWait);
        }
        return method.invoke(underlyingConnection, args);
    }

    public static Connection create(Connection toWrap, ConsistencyLevel connectionConsistencyLevel, GaleraStatus galeraStatus) throws SQLException {
        return (Connection) (Proxy.newProxyInstance(Connection.class.getClassLoader(),
                                                    new Class[] { Connection.class },
                                                    new GaleraProxyConnection(toWrap, connectionConsistencyLevel, galeraStatus)));
    }

    private void validate(ConsistencyLevel connectionConsistencyLevel) {
        if (!this.supportsSyncWait && connectionConsistencyLevel != ConsistencyLevel.CAUSAL_READS_ON
                && connectionConsistencyLevel != ConsistencyLevel.CAUSAL_READS_OFF) {
            LOG.warn("Your MariaDB version does not support syncWait and you are trying to configure connection consistency level to {}",
                     connectionConsistencyLevel);
        }
    }



}
