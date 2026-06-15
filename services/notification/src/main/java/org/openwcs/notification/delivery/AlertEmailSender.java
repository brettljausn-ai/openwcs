package org.openwcs.notification.delivery;

import java.util.Arrays;
import org.openwcs.notification.domain.AlertEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Email delivery channel (SMTP). Mirrors how the public contact form degrades when unconfigured:
 * if no {@code SPRING_MAIL_HOST} or no recipients are set, the alert is logged at INFO and skipped
 * rather than failing. Sending is also fully isolated — a send failure logs WARN and never
 * propagates back into evaluation.
 */
@Component
public class AlertEmailSender implements AlertDelivery {

    private static final Logger log = LoggerFactory.getLogger(AlertEmailSender.class);

    private final JavaMailSender mailSender;
    private final String mailHost;
    private final String[] recipients;
    private final String from;

    public AlertEmailSender(
            JavaMailSender mailSender,
            @Value("${spring.mail.host:}") String mailHost,
            @Value("${openwcs.notification.mail.to:}") String to,
            @Value("${openwcs.notification.mail.from:openwcs-alerts@localhost}") String from) {
        this.mailSender = mailSender;
        this.mailHost = mailHost;
        this.recipients = parseRecipients(to);
        this.from = from;
    }

    @Override
    public void onOpen(AlertEvent alert) {
        send("[openWCS " + alert.getSeverity() + "] " + alert.getArea() + " / " + alert.getMetric(),
                body("OPENED", alert), alert);
    }

    @Override
    public void onClear(AlertEvent alert) {
        send("[openWCS CLEARED] " + alert.getArea() + " / " + alert.getMetric(),
                body("CLEARED", alert), alert);
    }

    private void send(String subject, String text, AlertEvent alert) {
        if (mailHost == null || mailHost.isBlank() || recipients.length == 0) {
            log.info("email channel not configured (no SPRING_MAIL_HOST or recipients) — would have sent: {}",
                    subject);
            return;
        }
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(from);
            msg.setTo(recipients);
            msg.setSubject(subject);
            msg.setText(text);
            mailSender.send(msg);
            log.info("alert email sent for {} ({})", alert.getMetric(), alert.getId());
        } catch (Exception e) {
            log.warn("alert email delivery failed for {}: {}", alert.getMetric(), e.toString());
        }
    }

    private static String body(String transition, AlertEvent a) {
        return "Alert " + transition + "\n"
                + "Warehouse: " + a.getWarehouseId() + "\n"
                + "Area: " + a.getArea() + "\n"
                + "Metric: " + a.getMetric() + "\n"
                + "Severity: " + a.getSeverity() + "\n"
                + "Value: " + a.getValue() + " (threshold " + a.getThreshold() + ")\n"
                + "Since: " + a.getOpenedAt() + "\n"
                + "State: " + a.getState();
    }

    private static String[] parseRecipients(String csv) {
        if (csv == null || csv.isBlank()) {
            return new String[0];
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }
}
