package org.openwcs.txlog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // drives the outbox relay (build.md §5.5)
public class TxLogApplication {
    public static void main(String[] args) {
        SpringApplication.run(TxLogApplication.class, args);
    }
}
