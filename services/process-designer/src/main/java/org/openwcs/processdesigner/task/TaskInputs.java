package org.openwcs.processdesigner.task;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/** Small typed-coercion helpers for reading a task's resolved inputs. */
final class TaskInputs {

    private TaskInputs() {
    }

    static String string(Map<String, Object> in, String name) {
        Object v = in.get(name);
        return v == null ? null : String.valueOf(v);
    }

    static String requireString(Map<String, Object> in, String name) {
        String v = string(in, name);
        if (v == null || v.isBlank()) {
            throw new TaskExecutionException("Missing required task input: " + name);
        }
        return v;
    }

    static UUID uuid(Map<String, Object> in, String name) {
        String v = string(in, name);
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(v);
        } catch (IllegalArgumentException e) {
            throw new TaskExecutionException("Task input '" + name + "' is not a UUID: " + v);
        }
    }

    static UUID requireUuid(Map<String, Object> in, String name) {
        UUID v = uuid(in, name);
        if (v == null) {
            throw new TaskExecutionException("Missing required task input: " + name);
        }
        return v;
    }

    static BigDecimal decimal(Map<String, Object> in, String name) {
        Object v = in.get(name);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        try {
            return new BigDecimal(String.valueOf(v));
        } catch (NumberFormatException e) {
            throw new TaskExecutionException("Task input '" + name + "' is not a number: " + v);
        }
    }
}
