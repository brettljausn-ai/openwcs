package org.openwcs.notification.delivery;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Ensures a {@link JavaMailSender} bean always exists, even when no SMTP host is configured (Spring
 * Boot's mail auto-configuration only kicks in when {@code spring.mail.host} is set). The unconfigured
 * sender is never actually used: {@link AlertEmailSender} short-circuits to log-and-skip when there
 * is no host. This keeps the service (and its tests) bootable without a mail server.
 */
@Configuration
public class MailConfig {

    @Bean
    @ConditionalOnMissingBean(JavaMailSender.class)
    public JavaMailSender javaMailSender() {
        return new JavaMailSenderImpl();
    }
}
