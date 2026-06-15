package org.openwcs.assistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * AI assistant service. Powers an operator-facing chat over this openWCS instance's warehouse data
 * using the Anthropic Claude API. A request runs a manual tool-use agentic loop: Claude is given a
 * set of read-only tools, each of which is an HTTP GET against an existing service (orders, inventory,
 * flow, allocation) that propagates the caller's identity headers so RBAC + warehouse scope still
 * apply. The Anthropic API key + model + enabled flag are owned by master-data and fetched
 * server-side from its network-only internal endpoint; the key is never logged or returned.
 */
@SpringBootApplication
public class AssistantApplication {
    public static void main(String[] args) {
        SpringApplication.run(AssistantApplication.class, args);
    }
}
