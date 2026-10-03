package org.easyssf.receiver.spring.boot.jdbc;

import java.time.Duration;
import java.util.List;

import org.easyssf.receiver.jdbc.JdbcSsfExpiringStore;
import org.easyssf.receiver.jdbc.JdbcSsfStoreCleanup;
import org.springframework.context.SmartLifecycle;

/**
 * {@link JdbcSsfStoreCleanup} that runs while the application context is running.
 */
public class JdbcSsfStoreCleanupLifecycle extends JdbcSsfStoreCleanup implements SmartLifecycle {

    public JdbcSsfStoreCleanupLifecycle(List<JdbcSsfExpiringStore> stores, Duration interval) {
        super(stores, interval);
    }

}
