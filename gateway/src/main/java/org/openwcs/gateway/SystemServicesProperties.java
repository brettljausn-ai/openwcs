package org.openwcs.gateway;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The authoritative list of backend services the System info screen reports on, bound from
 * {@code openwcs.system.services} in application.yml. Each entry's {@code uri} reuses the same
 * {@code OPENWCS_URI_*} env overrides as the gateway routes, so dev (localhost) and compose
 * (service DNS) both work without a separate config.
 */
@Component
@ConfigurationProperties(prefix = "openwcs.system")
public class SystemServicesProperties {

    private List<Service> services = new ArrayList<>();

    public List<Service> getServices() {
        return services;
    }

    public void setServices(List<Service> services) {
        this.services = services;
    }

    /** A single service: its display name, runtime kind ({@code java} or {@code go}) and base URI. */
    public static class Service {
        private String name;
        private String kind = "java";
        private String uri;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getKind() {
            return kind;
        }

        public void setKind(String kind) {
            this.kind = kind;
        }

        public String getUri() {
            return uri;
        }

        public void setUri(String uri) {
            this.uri = uri;
        }
    }
}
