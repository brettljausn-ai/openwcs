package org.openwcs.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Global key/value system setting (e.g. {@code DEMO_MODE_ENABLED}). */
@Entity
@Table(name = "system_configuration")
public class SystemConfiguration {

    @Id
    @Column(name = "config_key", updatable = false, nullable = false)
    private String key;

    @Column(name = "config_value", nullable = false)
    private String value;

    public SystemConfiguration() {
    }

    public SystemConfiguration(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}
