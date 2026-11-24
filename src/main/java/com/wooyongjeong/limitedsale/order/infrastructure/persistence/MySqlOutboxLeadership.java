package com.wooyongjeong.limitedsale.order.infrastructure.persistence;

import com.wooyongjeong.limitedsale.order.domain.OutboxLeadership;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.stereotype.Component;

/**
 * The publisher right as a MySQL named lock. The lock belongs to one dedicated connection that is opened here, not
 * borrowed from the pool, so it can never be handed to a request and returned with the lock still on it. MySQL drops
 * the lock when that connection ends, however the process ends, and another instance's next attempt then succeeds.
 * Every method is called from the publisher's one thread.
 */
@Component
@ConditionalOnProperty(name = {"limited-sale.order.async.enabled", "limited-sale.order.outbox-publisher.enabled"},
    havingValue = "true")
class MySqlOutboxLeadership implements OutboxLeadership {
    static final String LOCK_NAME = "limited-sale:order-outbox-publisher";
    private static final Logger log = LoggerFactory.getLogger(MySqlOutboxLeadership.class);
    private static final String CONNECT_TIMEOUT_MS = "3000";
    private static final String SOCKET_TIMEOUT_MS = "10000";

    private final String url;
    private final String username;
    private final String password;
    private Connection connection;
    private boolean held;

    MySqlOutboxLeadership(DataSourceProperties properties) {
        this.url = properties.determineUrl();
        this.username = properties.determineUsername();
        this.password = properties.determinePassword();
    }

    @Override
    public boolean tryAcquire() {
        try {
            if (connection == null) {
                connection = open();
            }
            try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, 0)")) {
                statement.setString(1, LOCK_NAME);
                try (ResultSet result = statement.executeQuery()) {
                    held = result.next() && result.getInt(1) == 1;
                }
            }
            // Without the lock the connection stays open and is asked again a second later, instead of reconnecting.
            return held;
        } catch (SQLException failure) {
            log.warn("Could not ask for the outbox publisher lock: {}", failure.toString());
            close();
            return false;
        }
    }

    @Override
    public boolean stillHeld() {
        if (!held || connection == null) {
            return false;
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT IS_USED_LOCK(?) = CONNECTION_ID()")) {
            statement.setString(1, LOCK_NAME);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next() && result.getInt(1) == 1) {
                    return true;
                }
            }
        } catch (SQLException failure) {
            log.warn("Could not confirm the outbox publisher lock: {}", failure.toString());
        }
        close();
        return false;
    }

    @Override
    public void release() {
        if (held && connection != null) {
            try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                statement.setString(1, LOCK_NAME);
                statement.execute();
            } catch (SQLException failure) {
                // Closing the connection below releases it just the same.
                log.debug("RELEASE_LOCK failed; closing the connection instead: {}", failure.toString());
            }
        }
        close();
    }

    private Connection open() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password == null ? "" : password);
        // A connection that cannot answer must not hold the publisher thread for the driver's defaults.
        properties.setProperty("connectTimeout", CONNECT_TIMEOUT_MS);
        properties.setProperty("socketTimeout", SOCKET_TIMEOUT_MS);
        Connection opened = DriverManager.getConnection(url, properties);
        opened.setAutoCommit(true);
        return opened;
    }

    private void close() {
        held = false;
        Connection closing = connection;
        connection = null;
        if (closing != null) {
            try {
                closing.close();
            } catch (SQLException failure) {
                log.debug("Closing the outbox publisher connection failed: {}", failure.toString());
            }
        }
    }
}
