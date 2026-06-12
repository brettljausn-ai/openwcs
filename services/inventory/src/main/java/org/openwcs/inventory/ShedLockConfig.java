package org.openwcs.inventory;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ShedLock wiring (mirrors order-management). When this service runs as multiple replicas, a
 * {@code @SchedulerLock}-annotated {@code @Scheduled} job acquires a cluster-wide lock (a row in
 * {@code inventory.shedlock}) so it runs on only one replica per tick (the storage-density sweep
 * must not snapshot every block once per replica). {@code defaultLockAtMostFor} is a safety net
 * that releases the lock if a holder dies mid-run.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class ShedLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()
                        .withTableName("inventory.shedlock")
                        .build());
    }
}
