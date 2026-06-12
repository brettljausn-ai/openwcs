package org.openwcs.flow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FlowApplication {
    public static void main(String[] args) {
        SpringApplication.run(FlowApplication.class, args);
    }
}
