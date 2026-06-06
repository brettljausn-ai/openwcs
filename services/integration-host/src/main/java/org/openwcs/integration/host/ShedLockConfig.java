package org.openwcs.integration.host;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ShedLock wiring. When this service runs as multiple replicas, the {@code @SchedulerLock}-annotated
 * webhook dispatch loop acquires a cluster-wide lock (a row in {@code host_integration.shedlock}) so it
 * runs on only one replica per tick and doesn't send duplicate webhook callbacks. {@code defaultLockAtMostFor}
 * releases the lock if a holder dies mid-run.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class ShedLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()
                        .withTableName("host_integration.shedlock")
                        .build());
    }
}
