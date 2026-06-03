package org.openwcs.integration.host;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class HostIntegrationApplication {
    public static void main(String[] args) {
        SpringApplication.run(HostIntegrationApplication.class, args);
    }
}
