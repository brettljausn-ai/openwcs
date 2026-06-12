package org.openwcs.flow;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ShedLock wiring (mirrors slotting's). When this service runs as multiple replicas, each
 * {@code @SchedulerLock}-annotated job (the awaiting-slot retry sweep) acquires a cluster-wide lock
 * (a row in {@code flow.shedlock}) so it runs on only one replica per fire.
 * {@code defaultLockAtMostFor} releases the lock if a holder dies mid-run.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT5M")
public class ShedLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()
                        .withTableName("flow.shedlock")
                        .build());
    }
}
