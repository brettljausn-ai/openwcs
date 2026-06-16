package org.openwcs.processdesigner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Process-designer service: engine + storage for configurable handheld operator processes.
 *
 * <p>A process is a versioned JSON definition (a flow of handheld screens + task steps). The
 * CLIENT (handheld PWA) walks the screen flow and evaluates branch conditions locally so it
 * keeps working offline; the SERVER owns definition storage/versioning (exactly one ACTIVE per
 * process key), instance checkpointing for device-swap resume, and <b>task-step execution</b>
 * via a curated task library that calls existing audited services with the operator's forwarded
 * identity. See {@code docs/process-designer-spec.md}. REST under {@code /api/process-designer/**}.
 */
@SpringBootApplication
public class ProcessDesignerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ProcessDesignerApplication.class, args);
    }
}
